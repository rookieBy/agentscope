package io.agentscope.router.api.web;

import io.agentscope.router.common.tenant.RedisKeyFactory;
import io.agentscope.router.common.tenant.TenantContext;
import io.agentscope.router.common.tenant.TenantContextHolder;
import io.agentscope.router.common.constants.Constants;
import io.agentscope.router.common.exception.BizException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Servlet filter that resolves the per-request {@link TenantContext} from the
 * {@code X-Tenant-Id} header (and optionally {@code X-Request-Id}) and binds
 * it to the current thread via {@link TenantContextHolder}.
 *
 * <p>Lives in the {@code api} module so it can depend on Spring MVC. The
 * business module keeps only the pure POJOs (TenantContext, Holder, key
 * factory) so it stays framework-agnostic.
 *
 * <p>Behavior:
 * <ul>
 *   <li>No header → pass through (public endpoints like {@code /api/v1/health}
 *       work; tenant-scoped work raises {@code MISSING_TENANT_ID} downstream).</li>
 *   <li>Header present but invalid format → 400 with the BizException envelope
 *       (we write JSON here because the filter runs before
 *       {@code @RestControllerAdvice}).</li>
 *   <li>Valid header → bind to ThreadLocal + MDC, restore on exit.</li>
 * </ul>
 *
 * <p>Ordered {@code HIGHEST_PRECEDENCE+10} so the context is always available
 * downstream.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = req.getHeader(Constants.HEADER_TENANT_ID);
        if (tenantId == null || tenantId.isBlank()) {
            chain.doFilter(req, resp);
            return;
        }

        String normalized;
        try {
            normalized = RedisKeyFactory.requireTenantId(tenantId);
        } catch (BizException ex) {
            writeError(resp, ex);
            return;
        }

        String requestId = req.getHeader(Constants.HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        TenantContext ctx = new TenantContext(normalized, requestId, Instant.now());
        MDC.put("tenantId", normalized);
        MDC.put("requestId", requestId);
        try (TenantContextHolder.Scope ignored = TenantContextHolder.set(ctx)) {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove("tenantId");
            MDC.remove("requestId");
        }
    }

    private static void writeError(HttpServletResponse resp, BizException ex) throws IOException {
        log.warn("Rejecting request: code={}, message={}",
                ex.getErrorCode().code(), ex.getMessage());
        resp.setStatus(ex.getErrorCode().httpStatus());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getErrorCode().code());
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now().toString());
        resp.getWriter().write(toJson(body));
    }

    /** Tiny hand-rolled JSON to avoid pulling in ObjectMapper in a filter. */
    private static String toJson(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(v.toString())).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
