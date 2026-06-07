package io.agentscope.router.api.exception;

import io.agentscope.router.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception → JSON envelope. Lives in the {@code api} module so it can
 * depend on Spring MVC. Restricted by {@code basePackages} to intercept only
 * controllers under {@code io.agentscope.router.api}.
 */
@RestControllerAdvice(basePackages = "io.agentscope.router.api")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException ex) {
        log.warn("BizException: code={}, message={}", ex.getErrorCode().code(), ex.getMessage());
        return ResponseEntity
                .status(ex.getErrorCode().httpStatus())
                .body(envelope(ex.getErrorCode().code(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(envelope("BAD_REQUEST", ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(envelope("INTERNAL_ERROR", "Internal server error", Map.of()));
    }

    private static Map<String, Object> envelope(String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
