package io.agentscope.router.business.multimodal;

import io.agentscope.router.business.demo.DemoProperties;
import io.agentscope.router.common.tenant.RedisKeyFactory;
import io.agentscope.router.llm.config.MultimodalProperties;
import io.agentscope.router.llm.provider.MiniMaxMultimodalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * High-level orchestrator for image / video / music generation. Wraps
 * {@link MiniMaxMultimodalClient} with our tenant scoping, output-dir
 * persistence, and the {@link VideoTaskManager} state machine.
 *
 * <p>Three flows:
 * <ul>
 *   <li><b>Image</b> — synchronous. Calls minimax, returns the URLs
 *       immediately; no Redis state.</li>
 *   <li><b>Video</b> — async. Mints a PENDING {@link VideoTask}, submits to
 *       minimax, transitions to QUEUED with the provider task id, and
 *       returns the internal task id. The {@link VideoTaskWorker} picks the
 *       task up on its next sweep and updates the state until SUCCEEDED /
 *       FAILED; SSE subscribers are notified via Pub/Sub.</li>
 *   <li><b>Music</b> — synchronous. music-01 returns inline audio bytes
 *       (no task id, no polling). The bytes are persisted to
 *       {@code outputDir/_tmp_music-{uuid}.{ext}}; the caller is responsible
 *       for copying them to the user-visible file before the tmp is GC'd.</li>
 * </ul>
 */
@Service
public class MultimodalService {

    private static final Logger log = LoggerFactory.getLogger(MultimodalService.class);

    private final MiniMaxMultimodalClient client;
    private final VideoTaskManager manager;
    private final MultimodalProperties multimodalProps;
    private final DemoProperties demoProps;

    public MultimodalService(MiniMaxMultimodalClient client,
                             VideoTaskManager manager,
                             MultimodalProperties multimodalProps,
                             DemoProperties demoProps) {
        this.client = Objects.requireNonNull(client);
        this.manager = Objects.requireNonNull(manager);
        this.multimodalProps = Objects.requireNonNull(multimodalProps);
        this.demoProps = Objects.requireNonNull(demoProps);
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

    // ---- music (sync, persisted to tmp file) ----------------------------

    /**
     * Generate a music clip from structured lyrics + a style prompt.
     * Synchronous: music-2.6-free returns inline audio bytes; we write them
     * to a temp file under the configured {@code outputDir} and return its
     * path so the caller (agent tool) can copy it to a user-visible file.
     *
     * @param tenantId        non-blank, validated via {@link RedisKeyFactory}
     * @param lyrics          structured lyrics with {@code [verse]} / {@code [chorus]} markers
     * @param modelOverride   nullable; falls back to {@code agentscope.multimodal.minimax.music-model}
     * @param promptOverride  nullable; falls back to {@code agentscope.multimodal.minimax.music-prompt}
     */
    public Mono<MusicResult> generateMusic(String tenantId,
                                           String lyrics,
                                           String modelOverride,
                                           String promptOverride) {
        RedisKeyFactory.requireTenantId(tenantId);
        if (lyrics == null || lyrics.isBlank()) {
            return Mono.error(new IllegalArgumentException("lyrics is required"));
        }
        MultimodalProperties.Minimax mm = multimodalProps.getMinimax();
        String prompt = (promptOverride == null || promptOverride.isBlank())
                ? mm.getMusicPrompt() : promptOverride;
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new IllegalStateException(
                    "minimax music-prompt is not configured — set agentscope.multimodal.minimax.music-prompt"));
        }
        String model = (modelOverride == null || modelOverride.isBlank())
                ? mm.getMusicModel() : modelOverride;

        log.info("music.submit tenant={} model={} prompt='{}' lyrics.len={}",
                tenantId, model, prompt, lyrics.length());

        return client.generateMusic(model, prompt, lyrics)
                .flatMap(this::persistAudio)
                .doOnNext(r -> log.info("music.persisted tenant={} model={} path={} bytes={} durationMs={}",
                        tenantId, r.model(), r.tempPath(), r.fileSize(), r.audioLengthMs()));
    }

    private Mono<MusicResult> persistAudio(MiniMaxMultimodalClient.MusicResult raw) {
        return Mono.fromCallable(() -> {
            byte[] bytes = raw.audioBytes();
            if (bytes == null) {
                throw new MiniMaxMultimodalClient.MiniMaxMultimodalException(
                        "minimax returned audio_url but inline download is not implemented yet; url=" + raw.audioUrl());
            }
            Path outDir = Paths.get(demoProps.getOutputDir());
            Files.createDirectories(outDir);
            String ext = (raw.fileExtension() == null || raw.fileExtension().isBlank()) ? "mp3" : raw.fileExtension();
            String filename = "_tmp_music-" + UUID.randomUUID() + "." + ext;
            Path target = outDir.resolve(filename);
            Files.write(target, bytes);
            return new MusicResult(raw.model(), target.toString(),
                    bytes.length, raw.audioLengthMs(), ext);
        });
    }

    // ---- DTO ------------------------------------------------------------

    public record ImageResult(List<String> imageUrls, String model) {}

    /**
     * Music artifact persisted to disk by {@link #generateMusic}. The
     * {@code tempPath} points at a file under {@code outputDir} that the
     * caller (typically a {@code @Tool} method) is expected to copy to a
     * user-visible location.
     */
    public record MusicResult(String model,
                              String tempPath,
                              long fileSize,
                              long audioLengthMs,
                              String fileExtension) {}
}
