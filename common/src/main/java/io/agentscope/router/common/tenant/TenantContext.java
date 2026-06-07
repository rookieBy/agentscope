package io.agentscope.router.common.tenant;

import java.time.Instant;

/**
 * Per-request immutable tenant context. Carried in a {@link ThreadLocal} for
 * the lifetime of a single request handling thread (or virtual thread).
 *
 * @param tenantId validated tenant identifier (URL-safe, 1-64 chars)
 * @param requestId correlation id (auto-generated when client doesn't supply one)
 * @param receivedAt timestamp the request entered the system
 */
public record TenantContext(String tenantId, String requestId, Instant receivedAt) {

    public TenantContext {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }
}
