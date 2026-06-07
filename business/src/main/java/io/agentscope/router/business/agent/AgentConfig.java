package io.agentscope.router.business.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.router.business.demo.DemoProperties;
import io.agentscope.router.business.tools.MediaTools;
import io.agentscope.router.business.tools.PromoDemoTools;
import io.agentscope.router.business.tools.RequestContextStore;
import io.agentscope.router.llm.core.RoutingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the ReAct agents that back the agent-mode chat endpoint and the
 * AI-promo demo.
 *
 * <p>Two ReAct agents live here:
 * <ul>
 *   <li>{@code mediaAssistantAgent} — production agent for
 *       {@code /api/v1/chat/agent/stream}. Toolkit: {@code MediaTools} only.</li>
 *   <li>{@code promoDemoAgent} — <i>demo-only</i> agent for
 *       {@code /api/v1/demo/ai-promo}. Toolkit: {@code MediaTools} +
 *       {@code PromoDemoTools} (so it can use both the existing
 *       text_to_video / check_video_status tools and the new
 *       write_promo_copy / download_video_file tools). Only registered when
 *       {@code agentscope.demo.ai-promo.enabled=true} (typically under
 *       the smoke profile).</li>
 * </ul>
 *
 * <p>Tools are registered into a {@link Toolkit} via
 * {@link Toolkit#registerTool(Object)}, which scans the bean for any method
 * annotated with {@code @Tool} and turns each one into a callable schema.
 *
 * <p>Per-request tenant routing: each agent's {@code toolExecutionContext}
 * is pre-populated with a {@link RequestContextStore} so AgentScope can
 * inject the active {@link io.agentscope.router.business.tools.ToolContext}
 * straight into each tool method (no ThreadLocal, no executor wrapping).
 */
@Configuration
@EnableConfigurationProperties(DemoProperties.class)
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    // ---- production media agent ----------------------------------------

    @Bean
    public Toolkit mediaToolkit(MediaTools mediaTools) {
        ToolkitConfig config = ToolkitConfig.builder().build();
        Toolkit toolkit = new Toolkit(config);
        toolkit.registerTool(mediaTools);
        log.info("Registered media toolkit with {} tool(s): {}",
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

    // ---- AI-promo demo agent (only when enabled) -----------------------

    /**
     * The demo toolkit registers BOTH {@link MediaTools} (for text_to_video /
     * check_video_status) and {@link PromoDemoTools} (for write_promo_copy /
     * download_video_file). This is the educational point: the orchestrator
     * picks from a mixed toolset that spans sub-LLM calls, third-party HTTP
     * APIs, and local file IO.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public Toolkit promoDemoToolkit(MediaTools mediaTools, PromoDemoTools promoDemoTools) {
        ToolkitConfig config = ToolkitConfig.builder().build();
        Toolkit toolkit = new Toolkit(config);
        toolkit.registerTool(mediaTools);
        toolkit.registerTool(promoDemoTools);
        log.info("Registered promo-demo toolkit with {} tool(s): {}",
                toolkit.getToolNames().size(), toolkit.getToolNames());
        return toolkit;
    }

    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public ReActAgent promoDemoAgent(RoutingChatModel routingChatModel, Toolkit promoDemoToolkit) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("ai-promo-orchestrator")
                .description("Orchestrates an AI-promo video: copywriter sub-LLM, video API, poll, download.")
                .model(routingChatModel)
                .toolkit(promoDemoToolkit)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are an AI-promo orchestrator. You collaborate with THREE
                        "agents" to produce a short promotional video:

                          1) Copywriter sub-agent (tool: write_promo_copy) — generates
                             a vivid, scene-by-scene English script.
                          2) Video producer (tools: text_to_video + check_video_status) —
                             submits an async video task to minimax and polls for
                             completion.
                          3) File collector (tool: download_video_file) — writes the
                             finished .mp4 to the local output directory.

                        EXACT WORKFLOW (do not deviate):
                          STEP 1. Call write_promo_copy with:
                                  - topic: the user's topic verbatim
                                  - duration_seconds: the user's requested duration
                                  - language: 'en'
                          STEP 2. Take the 'script' field from the tool result. Call
                                  text_to_video with:
                                  - prompt: the script (use it as-is, do not edit)
                                  - duration: 6 OR 10 only (map the user's 30 to 6 or
                                    10; if the user specified 6 use 6, if 10 use 10).
                                    NEVER pass any other value; the API will reject it.
                                  - resolution: 768P
                          STEP 3. Take the 'taskId' field. Repeatedly call
                                  check_video_status until the state is SUCCEEDED or
                                  FAILED. If FAILED, return the failure_reason verbatim.
                          STEP 4. On SUCCEEDED, call download_video_file with:
                                  - task_id: the taskId from step 2
                                  - save_to: the EXACT 'save_to' value from the user
                                    message's metadata (it has the right filename and
                                    directory).
                          STEP 5. Reply in one short Chinese sentence naming the saved
                                  file path. Do NOT include the script body in your
                                  reply.

                        RULES:
                          - Surface tool errors verbatim to the user.
                          - Do not invent tool results. If a tool fails, stop and
                            report the error.
                          - Keep your own reasoning terse; the verbose activity log
                            is captured by the demo logger.
                        """)
                .maxIters(15)
                .build();
    }
}
