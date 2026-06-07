package io.agentscope.router.common.exception;

import java.util.Map;

/**
 * Domain exception carrying an {@link ErrorCode} and optional structured details.
 * The exception is converted to a JSON response by the global handler.
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.details = Map.of();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = Map.of();
    }

    public BizException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
