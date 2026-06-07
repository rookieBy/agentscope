package io.agentscope.router.business.demo;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.router.business.tools.MediaTools;
import io.agentscope.router.business.tools.ToolContext;
import io.agentscope.router.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the AI-promo demo end-to-end:
 *
 * <ol>
 *   <li>Builds the user message with {@code topic}, {@code duration},
 *       {@code saveTo} in metadata so the agent can read them without
 *       the LLM inventing values.</li>
 *   <li>Stamps a {@link ToolContext} into
 *       {@link MediaTools#setCurrentRequest} so that tool invocations
 *       (which run on a Reactor worker thread that does not inherit the
 *       request's {@link TenantContextHolder}) can still resolve the
 *       active tenantId.</li>
 *   <li>Streams agent {@link Event}s back as a {@code Flux<Map<String,Object>>}
 *       shaped like the existing {@code /api/v1/chat/agent/stream} payload
 *       so a single client-side parser works for both endpoints.</li>
 *   <li>Clears the {@link ToolContext} on terminal signal.</li>
 * </ol>
 *
 * <p>The whole bean is gated on
 * {@code agentscope.demo.ai-promo.enabled=true} via
 * {@link ConditionalOnProperty}; when the property is false this class
 * does not exist, the controller returns 404, and no ReAct agent is built.
 */
@Service
@ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
public class AiPromoDemoService {

    private static final Logger log = LoggerFactory.getLogger(AiPromoDemoService.class);

    private final ReActAgent promoDemoAgent;
    private final DemoProperties props;

    public AiPromoDemoService(ReActAgent promoDemoAgent, DemoProperties props) {
        this.promoDemoAgent = promoDemoAgent;
        this.props = props;
    }

    /**
     * Build the save path for a given duration. Public so the controller can
     * surface it in 4xx error responses.
     */
    public Path resolveSavePath(int duration) {
        return Paths.get(props.getOutputDir(), "ai-promo-" + duration + "s.mp4");
    }

    /**
     * Stream the demo. {@code tenantId} is taken from
     * {@link TenantContextHolder}; throws {@link IllegalStateException} if
     * no tenant context is bound (controller should never call this without
     * going through the filter).
     */
    public Flux<Map<String, Object>> stream(String topic, Integer duration, String language) {
        String tenantId = TenantContextHolder.currentTenantId();
        if (tenantId == null) {
            return Flux.error(new IllegalStateException(
                    "No TenantContext bound. Was TenantContextFilter applied?"));
        }
        int dur = (duration == null) ? props.getDefaultDuration() : duration;
        Path saveTo = resolveSavePath(dur);
        ToolContext ctx = ToolContext.forTenant(tenantId);
        String lang = (language == null || language.isBlank()) ? "en" : language;

        log.info("[orchestrator] start tenant={} requestId={} topic='{}' duration={}s lang={} saveTo={}",
                tenantId, ctx.requestId(), topic, dur, lang, saveTo);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenantId", tenantId);
        meta.put("requestId", ctx.requestId());
        meta.put("saveTo", saveTo.toString());
        meta.put("duration", dur);
        meta.put("language", lang);

        List<Msg> msgs = new ArrayList<>();
        msgs.add(Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("Generate a " + dur + "-second promotional video about: " + topic)
                .metadata(meta)
                .build());

        return promoDemoAgent.stream(msgs)
                .doOnSubscribe(s -> {
                    log.info("[orchestrator] subscribed tenant={} requestId={}",
                            tenantId, ctx.requestId());
                    MediaTools.setCurrentRequest(ctx);
                })
                .doFinally(sig -> {
                    log.info("[orchestrator] done tenant={} requestId={} sig={}",
                            tenantId, ctx.requestId(), sig);
                    MediaTools.clearCurrentRequest();
                })
                .map(event -> toChunk(event, ctx))
                .doOnError(err -> log.warn("[orchestrator] error tenant={} requestId={} cause={}",
                        tenantId, ctx.requestId(), err.getClass().getSimpleName()));
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
