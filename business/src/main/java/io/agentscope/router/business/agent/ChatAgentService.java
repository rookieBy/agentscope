package io.agentscope.router.business.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.router.business.tools.MediaTools;
import io.agentscope.router.business.tools.ToolContext;
import io.agentscope.router.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the AgentScope ReAct agent to the SSE streaming endpoint.
 *
 * <p>Each {@link Event} emitted by the agent is converted to a single SSE
 * chunk with a JSON payload describing the event type. The chunks form an
 * OpenAI-friendly event stream that the controller forwards verbatim.
 *
 * <p>The agent runs against the shared {@link io.agentscope.router.llm.core.RoutingChatModel},
 * so every LLM call inside the agent loop also benefits from TTFT-fallback
 * routing across providers.
 *
 * <p><b>ThreadLocal propagation.</b> The agent executes LLM calls and tool
 * invocations on virtual / Reactor worker threads that do not inherit the
 * request thread's {@link TenantContextHolder}. We therefore:
 * <ul>
 *   <li>stamp the {@code tenantId} into the user message's {@code metadata}
 *       so {@code RoutingChatModel} can pick it up from the message itself,</li>
 *   <li>bind the {@code ToolContext} into {@link MediaTools#setCurrentContext}
 *       for the duration of the agent stream so tool invocations can resolve
 *       the tenant id on whichever thread the framework dispatches them on.</li>
 * </ul>
 */
@Service
public class ChatAgentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAgentService.class);

    private final ReActAgent mediaAssistantAgent;

    public ChatAgentService(ReActAgent mediaAssistantAgent) {
        this.mediaAssistantAgent = mediaAssistantAgent;
    }

    /**
     * Stream agent events for a single user message. The {@code tenantId} is
     * taken from the current {@link io.agentscope.router.common.tenant.TenantContext}
     * and surfaced to tools via {@link ToolContext}.
     */
    public Flux<Map<String, Object>> stream(String userMessage) {
        String tenantId = TenantContextHolder.currentTenantId();
        if (tenantId == null) {
            return Flux.error(new IllegalStateException(
                    "No TenantContext bound. Was TenantContextFilter applied?"));
        }
        ToolContext ctx = ToolContext.forTenant(tenantId);
        log.info("agent.stream tenant={} requestId={} message.len={}",
                tenantId, ctx.requestId(),
                userMessage == null ? 0 : userMessage.length());

        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(userMessage == null ? "" : userMessage)
                .metadata(Map.of(
                        "tenantId", tenantId,
                        "requestId", ctx.requestId()))
                .build());

        return mediaAssistantAgent.stream(msgs)
                .doOnSubscribe(s -> MediaTools.setCurrentRequest(ctx))
                .doFinally(sig -> MediaTools.clearCurrentRequest())
                .map(event -> toChunk(event, ctx))
                .doOnError(err -> {
                    log.warn("agent.stream error tenant={} requestId={} cause={}",
                            tenantId, ctx.requestId(),
                            err.getClass().getSimpleName());
                });
    }

    private Map<String, Object> toChunk(Event event, ToolContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "agent");
        out.put("eventType", event.getType() == null ? "UNKNOWN" : event.getType().name());
        Msg msg = event.getMessage();
        if (msg == null) {
            out.put("content", "");
            return out;
        }
        out.put("role", msg.getRole() == null ? "?" : msg.getRole().name());
        out.put("content", textOf(msg));
        out.put("isLast", event.isLast());

        // Surface tool calls / tool results so clients can render them.
        if (msg.hasContentBlocks(ToolUseBlock.class)) {
            var blocks = msg.getContentBlocks(ToolUseBlock.class);
            out.put("toolCalls", blocks.stream().map(b -> Map.of(
                    "id", b.getId() == null ? "" : b.getId(),
                    "name", b.getName() == null ? "" : b.getName(),
                    "input", b.getInput() == null ? Map.of() : b.getInput()
            )).toList());
        }
        if (msg.hasContentBlocks(ToolResultBlock.class)) {
            var blocks = msg.getContentBlocks(ToolResultBlock.class);
            out.put("toolResults", blocks.stream().map(b -> Map.of(
                    "id", b.getId() == null ? "" : b.getId(),
                    "name", b.getName() == null ? "" : b.getName(),
                    "output", b.getOutput() == null ? "" : b.getOutput().toString(),
                    "isSuspended", b.isSuspended()
            )).toList());
        }
        return out;
    }

    private static String textOf(Msg msg) {
        if (msg.hasContentBlocks(TextBlock.class)) {
            return msg.getContentBlocks(TextBlock.class).stream()
                    .map(TextBlock::getText)
                    .filter(t -> t != null && !t.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        return msg.getTextContent() == null ? "" : msg.getTextContent();
    }
}
