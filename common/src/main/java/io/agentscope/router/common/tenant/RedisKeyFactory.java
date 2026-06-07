package io.agentscope.router.common.tenant;

import io.agentscope.router.common.constants.Constants;
import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Single source of truth for tenant-scoped Redis key names. Every Redis key
 * used by the system <strong>must</strong> be built through this class so
 * that:
 *
 * <ol>
 *   <li>Tenant id is always embedded (L1 isolation invariant).</li>
 *   <li>Tenant id format is validated exactly once.</li>
 *   <li>Key layout can be globally changed without touching call sites.</li>
 * </ol>
 *
 * <p>Key layout: {@code t:{tenantId}:{segment}:{id}}
 */
public final class RedisKeyFactory {

    private static final Pattern TENANT_PATTERN =
            Pattern.compile(Constants.TENANT_ID_PATTERN);

    private RedisKeyFactory() {}

    /** Validate and normalize a tenant id, throwing {@link BizException} on failure. */
    public static String requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.MISSING_TENANT_ID);
        }
        if (!TENANT_PATTERN.matcher(tenantId).matches()) {
            throw new BizException(ErrorCode.INVALID_TENANT_ID,
                    "tenantId='" + tenantId + "' does not match " + Constants.TENANT_ID_PATTERN);
        }
        return tenantId;
    }

    /** True if the input is a syntactically valid tenant id (no exception). */
    public static boolean isValid(String tenantId) {
        return tenantId != null && TENANT_PATTERN.matcher(tenantId).matches();
    }

    public static String videoTask(String tenantId, String taskId) {
        return build(requireTenantId(tenantId), Constants.REDIS_SEG_VIDEO_TASK, requireNonBlank(taskId, "taskId"));
    }

    public static String videoEventChannel(String tenantId, String taskId) {
        return build(requireTenantId(tenantId), Constants.REDIS_SEG_VIDEO_EVENT, requireNonBlank(taskId, "taskId"));
    }

    public static String routingCounter(String tenantId) {
        return build(requireTenantId(tenantId), Constants.REDIS_SEG_ROUTING_COUNTER, null);
    }

    public static String agentSession(String tenantId, String sessionId) {
        return build(requireTenantId(tenantId), Constants.REDIS_SEG_AGENT_SESSION, requireNonBlank(sessionId, "sessionId"));
    }

    public static String tenantConfig(String tenantId) {
        return build(requireTenantId(tenantId), Constants.REDIS_SEG_TENANT_CONFIG, null);
    }

    private static String build(String tenantId, String segment, String id) {
        StringBuilder sb = new StringBuilder(32);
        sb.append(Constants.REDIS_KEY_PREFIX).append(':').append(tenantId).append(':').append(segment);
        if (id != null) {
            sb.append(':').append(id);
        }
        return sb.toString();
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
