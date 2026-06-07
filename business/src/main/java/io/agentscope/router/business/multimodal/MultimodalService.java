package io.agentscope.router.business.multimodal;

import io.agentscope.router.common.tenant.RedisKeyFactory;
import io.agentscope.router.llm.provider.MiniMaxMultimodalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * High-level orchestrator for image / video generation. Wraps
 * {@link MiniMaxMultimodalClient} with our tenant scoping and the
 * {@link VideoTaskManager} state machine.
 *
 * <p>Two flows:
 * <ul>
 *   <li><b>Image</b> — synchronous. Calls minimax, returns the URLs
 *       immediately; no Redis state.</li>
 *   <li><b>Video</b> — async. Mints a PENDING {@link VideoTask}, submits to
 *       minimax, transitions to QUEUED with the provider task id, and
 *       returns the internal task id. The {@link VideoTaskWorker} picks the
 *       task up on its next sweep and updates the state until SUCCEEDED /
 *       FAILED; SSE subscribers are notified via Pub/Sub.</li>
 * </ul>
 */
@Service
public class MultimodalService {

    private static final Logger log = LoggerFactory.getLogger(MultimodalService.class);

    private final MiniMaxMultimodalClient client;
    private final VideoTaskManager manager;

    public MultimodalService(MiniMaxMultimodalClient client, VideoTaskManager manager) {
        this.client = Objects.requireNonNull(client);
        this.manager = Objects.requireNonNull(manager);
    }

    // ---- image (sync) ---------------------------------------------------

    public Mono<ImageResult> generateImage(String tenantId, String prompt,
                                           String aspectRatio, Integer n) {
        RedisKeyFactory.requireTenantId(tenantId);
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new IllegalArgumentException("prompt is required"));
        }
        log.info("image.submit tenant={} prompt.len={} aspect={} n={}",
                tenantId, prompt.length(), aspectRatio, n);
        return client.generateImage(prompt, aspectRatio, n)
                .map(urls -> new ImageResult(urls, client.imageModel()));
    }

    // ---- video (async) --------------------------------------------------

    public Mono<VideoTask> submitVideoTask(String tenantId, String prompt,
                                           Integer duration, String resolution,
                                           String firstFrameImageUrl) {
        RedisKeyFactory.requireTenantId(tenantId);
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new IllegalArgumentException("prompt is required"));
        }
        VideoTask task = VideoTask.newPending(tenantId, prompt,
                client.videoModel(), duration, resolution, firstFrameImageUrl);
        manager.save(task); // initial PENDING state visible immediately
        log.info("video.submit tenant={} taskId={} prompt.len={} duration={} resolution={}",
                tenantId, task.taskId(), prompt.length(), duration, resolution);
        return client.submitVideoGeneration(prompt, client.videoModel(),
                        duration, resolution, firstFrameImageUrl)
                .doOnNext(providerTaskId -> manager.transition(
                        task.withState(VideoTaskState.QUEUED, providerTaskId)))
                .doOnError(err -> manager.transition(
                        task.withFailure("submit: " + err.getMessage())))
                .thenReturn(manager.find(tenantId, task.taskId()).orElse(task));
    }

    public Optional<VideoTask> getTask(String tenantId, String taskId) {
        return manager.find(tenantId, taskId);
    }

    // ---- DTO ------------------------------------------------------------

    public record ImageResult(List<String> imageUrls, String model) {}
}
