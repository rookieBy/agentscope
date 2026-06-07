package io.agentscope.router.llm.core;

import java.time.Instant;

/**
 * Read-only health view of a single {@link ChatModelBase} instance, exposed to
 * the routing layer and the {@code /routing/status} endpoint.
 *
 * @param modelName         fully qualified name "provider:model"
 * @param tenantId          tenant this snapshot belongs to (per-tenant scoring)
 * @param score             0..1 composite health score (higher = healthier)
 * @param ttftEmaMs         smoothed time-to-first-token in ms
 * @param errorRateEma      0..1 smoothed failure ratio
 * @param consecutiveFailures current consecutive-failure count
 * @param cooldownUntil     epoch-ms after which the model is eligible again
 * @param updatedAt         wall-clock timestamp of the last metrics update
 */
public record HealthSnapshot(
        String modelName,
        String tenantId,
        double score,
        double ttftEmaMs,
        double errorRateEma,
        int consecutiveFailures,
        long cooldownUntil,
        Instant updatedAt
) {
    public static HealthSnapshot empty(String modelName, String tenantId) {
        return new HealthSnapshot(modelName, tenantId, 1.0, 0.0, 0.0, 0, 0L, Instant.EPOCH);
    }
}
