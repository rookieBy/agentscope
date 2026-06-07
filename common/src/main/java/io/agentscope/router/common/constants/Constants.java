package io.agentscope.router.common.constants;

/**
 * Centralized constants used across the router service. All keys, header names,
 * regex patterns and default timeouts live here so they can be referenced from
 * any module without creating a coupling.
 */
public final class Constants {

    private Constants() {}

    // --- HTTP / tenant identification ---------------------------------------

    /** Required request header carrying the tenant identifier. */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /** Request id header (auto-generated when missing). */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** Tenant id format: URL-safe, 1-64 chars. */
    public static final String TENANT_ID_PATTERN = "[a-zA-Z0-9_-]{1,64}";

    // --- Redis key namespace (L1 logical isolation) -------------------------

    public static final String REDIS_KEY_PREFIX = "t";

    public static final String REDIS_SEG_VIDEO_TASK = "video:task";
    public static final String REDIS_SEG_VIDEO_EVENT = "video:event";
    public static final String REDIS_SEG_ROUTING_COUNTER = "routing:counter";
    public static final String REDIS_SEG_AGENT_SESSION = "agent:session";
    public static final String REDIS_SEG_TENANT_CONFIG = "config";

    // --- Routing defaults --------------------------------------------------

    public static final int DEFAULT_TTFT_TIMEOUT_MS = 2_000;
    public static final int DEFAULT_HEALTH_WINDOW_SIZE = 100;
    public static final double DEFAULT_EMA_ALPHA = 0.3;
    public static final double DEFAULT_MIN_SCORE_THRESHOLD = 0.05;
    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 5;
    public static final long DEFAULT_COOLDOWN_MS = 30_000L;
    public static final int DEFAULT_VIDEO_TTL_HOURS = 24;

    // --- Spring component scan root ----------------------------------------

    public static final String BASE_PACKAGE = "io.agentscope.router";

    // --- Agentscope model bean names (for explicit @Qualifier usage) -------

    public static final String BEAN_ROUTING_CHAT_MODEL = "routingChatModel";
    public static final String BEAN_MODEL_REGISTRY = "modelRegistry";
}
