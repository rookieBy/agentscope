package io.agentscope.router.business.multimodal;

import io.agentscope.router.llm.provider.MiniMaxMultimodalClient;
import io.agentscope.router.llm.provider.MiniMaxMultimodalClient.VideoPollResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Background poller that reconciles in-flight video tasks with the minimax
 * gateway. Runs on a virtual-thread-friendly scheduled executor; for each
 * task in {@code PENDING} / {@code QUEUED} / {@code RUNNING} state it:
 *
 * <ol>
 *   <li>Calls {@code /v1/query/video_generation} with the stored
 *       {@code providerTaskId}.</li>
 *   <li>Translates the provider's status string
 *       ({@code Queueing} / {@code Processing} / {@code Success} /
 *       {@code Fail}) into our internal state machine.</li>
 *   <li>On success, calls {@code /v1/files/retrieve} to obtain the
 *       download URL and writes the terminal SUCCEEDED state.</li>
 *   <li>On failure, writes FAILED with the provider message.</li>
 * </ol>
 *
 * <p>Each transition is funneled through
 * {@link VideoTaskManager#transition(VideoTask)} so subscribers on the
 * per-task Pub/Sub channel are notified.
 */
@Component
public class VideoTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(VideoTaskWorker.class);

    private final VideoTaskManager manager;
    private final MiniMaxMultimodalClient client;

    public VideoTaskWorker(VideoTaskManager manager, MiniMaxMultimodalClient client) {
        this.manager = Objects.requireNonNull(manager);
        this.client = Objects.requireNonNull(client);
    }

    /**
     * Sweep every tenant on a fixed delay. The set of tenants with open
     * tasks is discovered each tick via
     * {@link VideoTaskManager#listTenantsWithOpenTasks()}; if it grows
     * beyond the smoke-test scale this should be cached in-memory and
     * refreshed on a much longer interval.
     */
    @Scheduled(fixedDelayString = "${agentscope.multimodal.poll-interval-ms:5000}",
               initialDelayString = "${agentscope.multimodal.poll-initial-delay-ms:3000}")
    public void pollAll() {
        for (String tenantId : manager.listTenantsWithOpenTasks()) {
            for (VideoTask t : manager.listForTenant(tenantId)) {
                if (t.state().isTerminal() || t.providerTaskId() == null) {
                    continue;
                }
                pollOne(t);
            }
        }
    }

    private void pollOne(VideoTask t) {
        client.queryVideoGeneration(t.providerTaskId())
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(poll -> applyPollResult(t, poll))
                .doOnError(err -> log.warn("video.poll.error tenant={} taskId={} providerTaskId={} cause={}",
                        t.tenantId(), t.taskId(), t.providerTaskId(),
                        err.getClass().getSimpleName() + ": " + err.getMessage()))
                .onErrorResume(err -> Mono.empty())
                .subscribe();
    }

    private void applyPollResult(VideoTask t, VideoPollResult poll) {
        switch (poll.status()) {
            case VideoPollResult.STATUS_QUEUEING -> {
                if (t.state() != VideoTaskState.QUEUED) {
                    manager.transition(t.withState(VideoTaskState.QUEUED, t.providerTaskId()));
                }
            }
            case VideoPollResult.STATUS_PROCESSING -> {
                if (t.state() != VideoTaskState.RUNNING) {
                    manager.transition(t.withState(VideoTaskState.RUNNING, t.providerTaskId()));
                }
            }
            case VideoPollResult.STATUS_SUCCESS -> handleSuccess(t, poll.fileId());
            case VideoPollResult.STATUS_FAIL -> manager.transition(
                    t.withFailure("minimax reported task failure"));
            default -> log.debug("video.poll.unknown_status tenant={} taskId={} status={}",
                    t.tenantId(), t.taskId(), poll.status());
        }
    }

    private void handleSuccess(VideoTask t, String fileId) {
        VideoTask withFile = (fileId != null && t.fileId() == null)
                ? manager.transition(t.withFileId(fileId))
                : t;
        client.retrieveFileDownloadUrl(Optional.ofNullable(withFile.fileId()).orElse(fileId))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(url -> manager.transition(withFile.withSuccess(url)))
                .doOnError(err -> manager.transition(
                        withFile.withFailure("file retrieve: " + err.getMessage())))
                .onErrorResume(err -> Mono.empty())
                .subscribe();
    }
}
