package io.agentscope.router.business.tools;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-invocation context threaded through every {@code @Tool} call. Holds the
 * tenant id so tool methods can scope their side effects to the caller
 * (e.g. a video task written to Redis gets the {@code t:{tenantId}:video:...}
 * prefix automatically).
 *
 * <p>Carries a request id and a wall-clock timestamp for log correlation.
 */
public record ToolContext(String tenantId, String requestId, Instant calledAt) {

    public static ToolContext forCurrentTenant() {
        return forTenant(io.agentscope.router.common.tenant.TenantContextHolder.currentTenantId());
    }

    public static ToolContext forTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                    "ToolContext requires a bound TenantContext (X-Tenant-Id header).");
        }
        return new ToolContext(tenantId, UUID.randomUUID().toString(), Instant.now());
    }
}
