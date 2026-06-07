package io.agentscope.router.business.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.router.business.tools.MediaTools;
import io.agentscope.router.business.tools.RequestContextStore;
import io.agentscope.router.llm.core.RoutingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the ReAct agent that backs the agent-mode chat endpoint.
 *
 * <p>Tools are registered into a {@link Toolkit} via
 * {@link Toolkit#registerTool(Object)}, which scans the bean for any method
 * annotated with {@code @Tool} and turns each one into a callable schema.
 *
 * <p>Per-request tenant routing: the agent's {@code toolExecutionContext} is
 * pre-populated with a {@link RequestContextStore} so AgentScope can inject
 * the active {@link io.agentscope.router.business.tools.ToolContext} straight
 * into each tool method (no ThreadLocal, no executor wrapping).
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public Toolkit mediaToolkit(MediaTools mediaTools) {
        ToolkitConfig config = ToolkitConfig.builder().build();
        Toolkit toolkit = new Toolkit(config);
        toolkit.registerTool(mediaTools);
        log.info("Registered toolkit with {} tool(s): {}",
                toolkit.getToolNames().size(), toolkit.getToolNames());
        return toolkit;
    }

    @Bean
    public ReActAgent mediaAssistantAgent(RoutingChatModel routingChatModel, Toolkit mediaToolkit) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("media-assistant")
                .description("Routes user requests to the best available LLM and invokes registered tools.")
                .model(routingChatModel)
                .toolkit(mediaToolkit)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are a media-aware assistant in front of a multi-LLM router.

                        Available tools:
                        - list_available_models: discover which LLMs are currently registered.
                        - model_health: get TTFT EMA, error rate and cooldown for a specific model.

                        When a user asks about model availability, routing, or health, ALWAYS
                        call the relevant tool before answering. Never invent model names or
                        health numbers. If a tool returns an error, surface the error verbatim
                        to the user. Keep replies concise; prefer tool calls over speculation.
                        """)
                .maxIters(5)
                .build();
    }
}
