package io.agentscope.router.common.exception;

/**
 * Business error codes returned to API clients. Each code has a stable string
 * identifier (used in JSON responses and logs) and a default HTTP status.
 */
public enum ErrorCode {

    MISSING_TENANT_ID("MISSING_TENANT_ID", 400, "X-Tenant-Id header is required"),
    INVALID_TENANT_ID("INVALID_TENANT_ID", 400, "X-Tenant-Id must match [a-zA-Z0-9_-]{1,64}"),

    PROVIDER_NOT_ENABLED("PROVIDER_NOT_ENABLED", 400, "Provider is not enabled in configuration"),
    MODEL_NOT_FOUND("MODEL_NOT_FOUND", 404, "Requested model is not registered"),
    NO_HEALTHY_CANDIDATE("NO_HEALTHY_CANDIDATE", 503, "All candidate models are unhealthy"),

    VIDEO_TASK_NOT_FOUND("VIDEO_TASK_NOT_FOUND", 404, "Video task not found for this tenant"),
    VIDEO_TASK_FORBIDDEN("VIDEO_TASK_FORBIDDEN", 403, "Video task belongs to a different tenant"),

    UPSTREAM_LLM_ERROR("UPSTREAM_LLM_ERROR", 502, "Upstream LLM returned an error"),
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "Internal server error");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
    public String defaultMessage() { return defaultMessage; }
}
