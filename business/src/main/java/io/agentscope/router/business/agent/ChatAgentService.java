package io.agentscope.router.business.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.pipeline.MsgHub;
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
 * Bridges a 2-agent ReAct collaboration to the SSE streaming endpoint.
 *
 * <p>The two agents (routing-models → routing-advisor) are joined by a
 * {@link MsgHub} with {@code autoBroadcast=true}: when {@code routingModelsAgent}
 * finishes a reasoning round and publishes a summary, that message is
 * automatically pushed into {@code routingAdvisorAgent}'s history before its
 * turn starts. The advisor then reads the published model/health data and
 * recommends the best model.
 *
 * <p>{@link Event}s from both agents are concatenated (in deterministic
 * order: models first, then advisor) and each is converted to a single SSE
 * chunk via {@link #toChunk}, so clients see a single, ordered stream.
 *
 * <p>Both agents run against the shared
 * {@link io.agentscope.router.llm.core.RoutingChatModel}, so every LLM call
 * inside the agent loops also benefits from TTFT-fallback routing across
 * providers.
 *
 * <p><b>ThreadLocal propagation.</b> The agents execute LLM calls and tool
 * invocations on virtual / Reactor worker threads that do not inherit the
 * request thread's {@link TenantContextHolder}. We therefore:
 * <ul>
 *   <li>stamp the {@code tenantId} into the user message's {@code metadata}
 *       so {@code RoutingChatModel} can pick it up from the message itself,</li>
 *   <li>bind the {@code ToolContext} into {@link MediaTools#setCurrentRequest}
 *       for the duration of the agent streams so tool invocations can resolve
 *       the tenant id on whichever thread the framework dispatches them on.</li>
 * </ul>
 */
@Service
public class ChatAgentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAgentService.class);

    private final ReActAgent routingModelsAgent;
    private final ReActAgent routingAdvisorAgent;

    public ChatAgentService(ReActAgent routingModelsAgent,
                            ReActAgent routingAdvisorAgent) {
        this.routingModelsAgent = routingModelsAgent;
        this.routingAdvisorAgent = routingAdvisorAgent;
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

        // Build the MsgHub with autoBroadcast. The hub is entered (entered=true)
        // inside the Flux pipeline so that both agent subscriptions see the
        // active hub context, and is closed on terminal signal.
        MsgHub hub = MsgHub.builder()
                .name("routing-hub")
                .participants(routingModelsAgent, routingAdvisorAgent)
                .enableAutoBroadcast(true)
                .build();

        StreamOptions opts = StreamOptions.defaults();

        return Flux.using(
                        () -> {
                            log.info("[hub:routing-hub] enter tenant={} requestId={}",
                                    tenantId, ctx.requestId());
                            return hub.enter().block();
                        },
                        activeHub -> {
                            log.info("[hub:routing-hub] activated tenant={} requestId={} participants={}",
                                    tenantId, ctx.requestId(), activeHub.getParticipants().size());
                            return Flux.concat(
                                    routingModelsAgent.stream(msgs, opts),
                                    routingAdvisorAgent.stream(msgs, opts)
                            );
                        },
                        activeHub -> {
                            log.info("[hub:routing-hub] exit tenant={} requestId={}",
                                    tenantId, ctx.requestId());
                            activeHub.exit().block();
                        })
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
