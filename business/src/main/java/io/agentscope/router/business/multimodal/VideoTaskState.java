package io.agentscope.router.business.multimodal;

/**
 * Lifecycle states for a {@link VideoTask}. Persisted in Redis Hash as the
 * string form of the enum constant and in Pub/Sub events as part of the JSON
 * payload.
 */
public enum VideoTaskState {
    /** Just created locally; provider has not been contacted yet. */
    PENDING,
    /** Provider accepted the task and returned a {@code providerTaskId}. */
    QUEUED,
    /** Provider reports the task is actively being processed. */
    RUNNING,
    /** Provider finished; {@code videoUrl} is populated. */
    SUCCEEDED,
    /** Terminal failure; {@code failureReason} describes the cause. */
    FAILED;

    /** True for SUCCEEDED / FAILED — used by the SSE listener to auto-close. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
