package io.agentscope.router.api.controller;

import io.agentscope.router.api.dto.AiPromoRequest;
import io.agentscope.router.business.demo.AiPromoDemoService;
import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;
import io.agentscope.router.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo endpoints that illustrate how {@code agentscope} coordinates multiple
 * agents. The whole controller is gated on
 * {@code agentscope.demo.ai-promo.enabled=true}; when the property is false
 * the class is absent and {@code POST /api/v1/demo/ai-music} returns 404.
 *
 * <p>The current endpoint streams the AI-music orchestration as SSE. Each
 * chunk is a JSON object on a single {@code data:} line, so a single
 * client-side parser can handle both this endpoint and
 * {@code /api/v1/chat/agent/stream}.
 */
@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /**
     * music-01 (synchronous) does not impose a server-side duration bound.
     * We still cap the user input to keep lyrics length sensible and the
     * per-request cost bounded; the cap is a soft UX guard, not a provider
     * requirement.
     */
    private static final int MIN_DURATION_SEC = 1;
    private static final int MAX_DURATION_SEC = 600;

    private final AiPromoDemoService aiPromoDemoService;

    public DemoController(AiPromoDemoService aiPromoDemoService) {
        this.aiPromoDemoService = aiPromoDemoService;
    }

    /**
     * Stream the AI-music orchestration. Same shape as the production
     * agent-stream endpoint, so a single SSE client works for both.
     *
     * <p>Validation order:
     * <ol>
     *   <li>{@code X-Tenant-Id} present (enforced upstream by
     *       {@code TenantContextFilter}).</li>
     *   <li>{@code topic} non-blank.</li>
     *   <li>{@code duration}, if provided, in {@code [1, 600]} seconds.</li>
     * </ol>
     */
    @PostMapping(value = "/ai-music", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> aiMusicStream(@RequestBody(required = false) AiPromoRequest body) {
        String tenantId = TenantContextHolder.currentTenantId();
        if (tenantId == null) {
            return Flux.error(new BizException(ErrorCode.MISSING_TENANT_ID));
        }
        if (body == null || body.topic() == null || body.topic().isBlank()) {
            return Flux.error(new BizException(ErrorCode.INVALID_TENANT_ID,
                    "Request body must contain a non-blank 'topic' field"));
        }
        if (body.duration() != null
                && (body.duration() < MIN_DURATION_SEC || body.duration() > MAX_DURATION_SEC)) {
            return Flux.error(new BizException(ErrorCode.INVALID_VIDEO_DURATION,
                    "duration must be between " + MIN_DURATION_SEC + " and "
                            + MAX_DURATION_SEC + " seconds, got " + body.duration(),
                    Map.of("duration", body.duration(),
                            "min", MIN_DURATION_SEC,
                            "max", MAX_DURATION_SEC)));
        }

        log.info("demo.ai-music.start tenant={} topic='{}' duration={}s language='{}'",
                tenantId, body.topic(), body.duration(), body.language());
        return aiPromoDemoService.stream(body.topic(), body.duration(), body.language())
                .map(this::toSseChunk)
                .doOnError(err -> log.warn("demo.ai-music.error tenant={} cause={}",
                        tenantId, err.getClass().getSimpleName()));
    }

    private String toSseChunk(Map<String, Object> payload) {
        return "data: " + toJson(payload) + "\n\n";
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v instanceof Map<?, ?> map) {
                sb.append(toJson(toStrMap(map)));
            } else if (v instanceof Iterable<?> it) {
                sb.append('[');
                boolean firstItem = true;
                for (Object item : it) {
                    if (!firstItem) sb.append(',');
                    firstItem = false;
                    if (item == null) sb.append("null");
                    else if (item instanceof Map<?, ?> im) sb.append(toJson(toStrMap(im)));
                    else sb.append('"').append(escape(item.toString())).append('"');
                }
                sb.append(']');
            } else sb.append('"').append(escape(v.toString())).append('"');
        }
        return sb.append('}').toString();
    }

    private static Map<String, Object> toStrMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(e.getKey() == null ? "" : e.getKey().toString(), e.getValue());
        }
        return out;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
