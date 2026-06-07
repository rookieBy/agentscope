package io.agentscope.router.api.controller;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.router.api.dto.ChatRequest;
import io.agentscope.router.business.agent.ChatAgentService;
import io.agentscope.router.business.chat.ChatService;
import io.agentscope.router.common.tenant.TenantContextHolder;
import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;
import io.agentscope.router.llm.core.RoutingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streaming chat endpoint. Accepts a simple OpenAI-shaped messages array
 * and streams the routed model's responses as Server-Sent Events.
 *
 * <p>Each {@link ChatResponse} is serialized as a JSON object on a single
 * {@code data:} line, then flushed. Clients can parse line-by-line.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ChatAgentService chatAgentService;
    private final String routingModelLabel;

    public ChatController(ChatService chatService,
                          ChatAgentService chatAgentService,
                          RoutingChatModel routingChatModel) {
        this.chatService = chatService;
        this.chatAgentService = chatAgentService;
        this.routingModelLabel = routingChatModel.getModelName();
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody(required = false) ChatRequest body) {
        String tenantId = TenantContextHolder.currentTenantId();
        if (tenantId == null) {
            return Flux.error(new BizException(ErrorCode.MISSING_TENANT_ID));
        }
        if (body == null || body.messages() == null || body.messages().isEmpty()) {
            return Flux.error(new BizException(ErrorCode.INVALID_TENANT_ID,
                    "Request body must contain a non-empty 'messages' array"));
        }
        log.info("chat.stream tenant={} messageCount={}", tenantId, body.messages().size());
        return chatService.stream(body.messages())
                .map(this::toSseChunk)
                .doOnError(err -> log.warn("chat.stream error tenant={} cause={}",
                        tenantId, err.getClass().getSimpleName()));
    }

    /**
     * Agent-mode endpoint: routes through the {@link io.agentscope.core.ReActAgent},
     * which may invoke {@code @Tool}-annotated methods registered in the
     * toolkit. Emits one SSE chunk per agent event (REASONING, TOOL_RESULT,
     * AGENT_RESULT, etc.) so the client can render the full thought process.
     *
     * <p>Request body: {@code {"message": "..."}}. The X-Tenant-Id header is
     * still required and is propagated into the tool context automatically.
     */
    @PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> agentStream(@RequestBody(required = false) Map<String, String> body) {
        String tenantId = TenantContextHolder.currentTenantId();
        if (tenantId == null) {
            return Flux.error(new BizException(ErrorCode.MISSING_TENANT_ID));
        }
        String message = body == null ? null : body.get("message");
        if (message == null || message.isBlank()) {
            return Flux.error(new BizException(ErrorCode.INVALID_TENANT_ID,
                    "Request body must contain a non-empty 'message' field"));
        }
        log.info("chat.agent.stream tenant={} message.len={}", tenantId, message.length());
        return chatAgentService.stream(message)
                .map(this::toAgentSseChunk)
                .doOnError(err -> log.warn("chat.agent.stream error tenant={} cause={}",
                        tenantId, err.getClass().getSimpleName()));
    }

    private String toAgentSseChunk(Map<String, Object> payload) {
        return "data: " + toJson(payload) + "\n\n";
    }

    private String toSseChunk(ChatResponse cr) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "model");
        payload.put("model", routingModelLabel);
        payload.put("content", extractText(cr));
        return "data: " + toJson(payload) + "\n\n";
    }

    private static String extractText(ChatResponse cr) {
        if (cr == null) return "";
        var blocks = cr.getContent();
        if (blocks == null || blocks.isEmpty()) return "";
        for (var b : blocks) {
            if (b instanceof TextBlock tb) {
                String t = tb.getText();
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return "";
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
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
