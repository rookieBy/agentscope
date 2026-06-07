package io.agentscope.router.business.multimodal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal record of a text-to-video task. Lives in a Redis Hash keyed by
 * {@code t:{tenantId}:video:task:{taskId}} with a 24h TTL, and is published
 * to {@code t:{tenantId}:video:event:{taskId}} whenever its state changes.
 *
 * <p><b>Fields vs identifiers.</b>
 * <ul>
 *   <li>{@code taskId} — our internal id (UUID, used in API responses).</li>
 *   <li>{@code providerTaskId} — the id returned by minimax
 *       ({@code /v1/video_generation}); we poll {@code /v1/query/video_generation}
 *       with this id.</li>
 *   <li>{@code tenantId} — used to scope both the Hash key and the Pub/Sub channel.</li>
 * </ul>
 *
 * <p><b>State machine.</b>
 * <pre>
 *   PENDING ──submit──▶ QUEUED ──poll(proc)──▶ RUNNING ──poll(success)──▶ SUCCEEDED
 *      │                  │                      │                          │
 *      │                  │                      └─poll(fail)──▶ FAILED ◀──┘
 *      │                  │                                  ▲
 *      │                  └─poll(fail)────────▶ FAILED ─────┘
 *      │
 *      └─ submit error ──▶ FAILED
 * </pre>
 */
public record VideoTask(
        String taskId,
        String tenantId,
        String prompt,
        String model,
        Integer duration,
        String resolution,
        String firstFrameImageUrl,
        VideoTaskState state,
        String providerTaskId,
        String fileId,
        String videoUrl,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {

    @JsonCreator
    public VideoTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static VideoTask newPending(String tenantId,
                                       String prompt,
                                       String model,
                                       Integer duration,
                                       String resolution,
                                       String firstFrameImageUrl) {
        Instant now = Instant.now();
        return new VideoTask(
                UUID.randomUUID().toString(),
                tenantId,
                prompt,
                model,
                duration,
                resolution,
                firstFrameImageUrl,
                VideoTaskState.PENDING,
                null,
                null,
                null,
                null,
                now,
                now);
    }

    public VideoTask withState(VideoTaskState newState, String providerTaskId) {
        return new VideoTask(taskId, tenantId, prompt, model, duration, resolution,
                firstFrameImageUrl, newState, providerTaskId, fileId, videoUrl,
                failureReason, createdAt, Instant.now());
    }

    public VideoTask withFileId(String newFileId) {
        return new VideoTask(taskId, tenantId, prompt, model, duration, resolution,
                firstFrameImageUrl, state, providerTaskId, newFileId, videoUrl,
                failureReason, createdAt, Instant.now());
    }

    public VideoTask withSuccess(String url) {
        return new VideoTask(taskId, tenantId, prompt, model, duration, resolution,
                firstFrameImageUrl, VideoTaskState.SUCCEEDED, providerTaskId, fileId, url,
                null, createdAt, Instant.now());
    }

    public VideoTask withFailure(String reason) {
        return new VideoTask(taskId, tenantId, prompt, model, duration, resolution,
                firstFrameImageUrl, VideoTaskState.FAILED, providerTaskId, fileId, videoUrl,
                reason, createdAt, Instant.now());
    }

    /** Marker for "I am the new envelope being serialized to JSON". */
    @JsonProperty("state")
    public String stateName() {
        return state.name();
    }
}
