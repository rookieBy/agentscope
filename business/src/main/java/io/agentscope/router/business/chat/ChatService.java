package io.agentscope.router.business.chat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.router.common.tenant.TenantContextHolder;
import io.agentscope.router.llm.core.RoutingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a chat call. Translates a simple {@code messages:[{role,content}]}
 * DTO into AgentScope {@link Msg} objects, hands them to the
 * {@link RoutingChatModel}, and returns the raw response stream.
 *
 * <p>Function-call / ReAct logic is layered on top in M4 via
 * {@code business.tools.MediaTools} and {@code business.agent.AgentConfig}.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RoutingChatModel routingChatModel;

    public ChatService(RoutingChatModel routingChatModel) {
        this.routingChatModel = routingChatModel;
    }

    public Flux<ChatResponse> stream(String userMessage) {
        return stream(List.of(Map.of("role", "user", "content", userMessage)));
    }

    /**
     * @param messages list of {@code {role, content}} maps; only "user" / "assistant" /
     *                 "system" are recognized.
     */
    public Flux<ChatResponse> stream(List<Map<String, String>> messages) {
        String tenantId = TenantContextHolder.currentTenantId();
        if (tenantId == null) {
            return Flux.error(new IllegalStateException(
                    "No TenantContext bound. Was TenantContextFilter applied?"));
        }
        List<Msg> msgs = toAgentScopeMessages(messages);
        log.debug("chat.stream tenant={} msgCount={} firstRole={}",
                tenantId, msgs.size(), msgs.isEmpty() ? "?" : msgs.get(0).getRole());
        return routingChatModel.stream(msgs, List.<ToolSchema>of(), GenerateOptions.builder().build());
    }

    private static List<Msg> toAgentScopeMessages(List<Map<String, String>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Msg> out = new ArrayList<>(raw.size());
        for (Map<String, String> m : raw) {
            String role = m.getOrDefault("role", "user").toLowerCase();
            String content = m.getOrDefault("content", "");
            MsgRole mr = switch (role) {
                case "assistant" -> MsgRole.ASSISTANT;
                case "system"    -> MsgRole.SYSTEM;
                default          -> MsgRole.USER;
            };
            out.add(Msg.builder().role(mr).textContent(content).build());
        }
        return out;
    }
}
