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
 * <p><b>Production path</b> ({@code /api/v1/chat/agent/stream}) uses TWO
 * independent ReAct agents coordinated via a {@code MsgHub} at request time:
 * <ul>
 *   <li>{@code routingModelsAgent} — discovers available models and their
 *       health, via {@code MediaTools} ({@code list_available_models},
 *       {@code model_health}).</li>
 *   <li>{@code routingAdvisorAgent} — pure-LLM advisor with no tools. Reads
 *       the data emitted by {@code routingModelsAgent} from the shared hub
 *       and recommends the best model.</li>
 * </ul>
 *
 * <p><b>Demo path</b> ({@code /api/v1/demo/ai-promo}) uses THREE independent
 * ReAct agents, again coordinated by a {@code MsgHub}:
 * <ul>
 *   <li>{@code copywriterAgent} — pure-LLM, no tools. Generates the script.</li>
 *   <li>{@code videoProducerAgent} — owns {@code MediaTools} video methods
 *       ({@code text_to_video}, {@code check_video_status}).</li>
 *   <li>{@code fileCollectorAgent} — owns {@code PromoDemoTools.download_video_file}.</li>
 * </ul>
 * The three demo agents are gated on
 * {@code agentscope.demo.ai-promo.enabled=true} (typically the smoke profile).
 *
 * <p>Each agent gets its own {@link RequestContextStore} so AgentScope can
 * inject the active {@link io.agentscope.router.business.tools.ToolContext}
 * straight into each tool method (no ThreadLocal, no executor wrapping).
 */
@Configuration
@EnableConfigurationProperties(DemoProperties.class)
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    // ---- production: 2 agents, 1 toolkit --------------------------------

    @Bean
    public Toolkit mediaToolkit(MediaTools mediaTools) {
        ToolkitConfig config = ToolkitConfig.builder().build();
        Toolkit toolkit = new Toolkit(config);
        toolkit.registerTool(mediaTools);
        log.info("Registered media toolkit with {} tool(s): {}",
                toolkit.getToolNames().size(), toolkit.getToolNames());
        return toolkit;
    }

    /**
     * Production agent #1: queries {@code list_available_models} /
     * {@code model_health} and publishes the results to the hub so the
     * advisor can read them. maxIters kept small (5) — the workflow is
     * "list then health-probe a couple of candidates, then stop".
     */
    @Bean
    public ReActAgent routingModelsAgent(RoutingChatModel routingChatModel, Toolkit mediaToolkit) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("routing-models")
                .description("Lists available LLM models and probes their health.")
                .model(routingChatModel)
                .toolkit(mediaToolkit)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are the models-introspection half of a 2-agent
                        routing team. You have access to two tools:

                          - list_available_models: discover which LLMs are
                            currently registered in the router.
                          - model_health: get TTFT EMA, error rate, and
                            cooldown for a specific model.

                        Your job:
                          1. Call list_available_models ONCE.
                          2. If the user asked about a specific model or a
                             specific concern (latency, errors, cooldown), call
                             model_health for the relevant qualified names.
                          3. Summarise the model list (and any health
                             snapshots you took) in a short paragraph so your
                             peer advisor agent can recommend a model to the
                             user.

                        Do NOT recommend a model yourself. That is the
                        advisor's job. Do NOT call text_to_image, text_to_video,
                        or any video tools — those are not part of routing.
                        """)
                .maxIters(5)
                .build();
    }

    /**
     * Production agent #2: pure-LLM advisor. No tools. Reads the hub-shared
     * history (where {@code routingModelsAgent} has already published the
     * model list and any health probes) and recommends the best model.
     */
    @Bean
    public ReActAgent routingAdvisorAgent(RoutingChatModel routingChatModel) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("routing-advisor")
                .description("Recommends the best LLM model based on peer-shared data.")
                .model(routingChatModel)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are the advisor half of a 2-agent routing team.
                        You do NOT have tools — instead you read the shared
                        conversation history on the MsgHub (where your peer
                        agent 'routing-models' has just published a summary
                        of available models and any health snapshots).

                        Given:
                          - the user's original question (in the conversation),
                          - and the model / health data your peer published,

                        recommend ONE specific qualified model name in the
                        format "provider:model" (e.g. "minimax:MiniMax-M3").
                        Justify the choice in 1-2 short sentences, citing the
                        health numbers when relevant. If the peer agent
                        surfaced an error, relay it verbatim to the user.
                        """)
                .maxIters(2)
                .build();
    }

    // ---- demo (3 agents, 2 toolkits) -----------------------------------

    /**
     * Demo toolkit for the video producer agent. Registers {@link MediaTools}
     * (its {@code text_to_video} and {@code check_video_status} methods).
     * Gated on {@code agentscope.demo.ai-promo.enabled=true}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public Toolkit videoProducerToolkit(MediaTools mediaTools) {
        ToolkitConfig config = ToolkitConfig.builder().build();
        Toolkit toolkit = new Toolkit(config);
        toolkit.registerTool(mediaTools);
        log.info("Registered videoProducer toolkit with {} tool(s): {}",
                toolkit.getToolNames().size(), toolkit.getToolNames());
        return toolkit;
    }

    /**
     * Demo toolkit for the file collector agent. Registers {@link PromoDemoTools}
     * (its {@code download_video_file} method). Gated on
     * {@code agentscope.demo.ai-promo.enabled=true}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public Toolkit fileCollectorToolkit(PromoDemoTools promoDemoTools) {
        ToolkitConfig config = ToolkitConfig.builder().build();
        Toolkit toolkit = new Toolkit(config);
        toolkit.registerTool(promoDemoTools);
        log.info("Registered fileCollector toolkit with {} tool(s): {}",
                toolkit.getToolNames().size(), toolkit.getToolNames());
        return toolkit;
    }

    /**
     * Demo agent #1: copywriter. Pure LLM, no tools. Generates the script
     * that the next agent in the hub will hand to the video API.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public ReActAgent copywriterAgent(RoutingChatModel routingChatModel) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("copywriter")
                .description("Generates a vivid scene-by-scene promotional script.")
                .model(routingChatModel)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are the copywriter in a 3-agent AI-promo team.

                        Read the user's request from the conversation. Generate
                        a vivid, scene-by-scene English script of 3-5 scenes
                        that together feel like a promotional clip:

                          - Embed minimax-compatible camera commands in square
                            brackets where they add visual interest, e.g.
                            [Push in], [Pan left], [Static shot], [Zoom out].
                            Use at most one command per scene.
                          - Output language: read the 'language' field from the
                            user message's metadata (default 'en').
                          - Honour the 'duration' field from the user message's
                            metadata to decide scene length.
                          - Return ONLY the script. No preamble, no markdown,
                            no closing line.

                        Your output will be consumed by the next agent on the
                        hub (video producer), which will use it verbatim as
                        the prompt for text_to_video.
                        """)
                .maxIters(2)
                .build();
    }

    /**
     * Demo agent #2: video producer. Owns the {@code MediaTools} video
     * methods. Reads the script published by {@code copywriterAgent} on the
     * hub, submits a video task, polls for completion.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public ReActAgent videoProducerAgent(RoutingChatModel routingChatModel,
                                          Toolkit videoProducerToolkit) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("video-producer")
                .description("Submits async text-to-video tasks and polls for completion.")
                .model(routingChatModel)
                .toolkit(videoProducerToolkit)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are the video producer in a 3-agent AI-promo team.

                        EXACT WORKFLOW:
                          STEP 1. Read the script that the copywriter agent
                                  just published on the shared MsgHub. Use it
                                  verbatim as the 'prompt' for text_to_video.
                          STEP 2. Call text_to_video with:
                                  - prompt: the copywriter's script (use as-is,
                                    do not edit).
                                  - duration: read the 'duration' field from
                                    the user message's metadata. Allowed
                                    values are 6 or 10. If the user asked
                                    for something else, fall back to 6.
                                  - resolution: 768P.
                          STEP 3. From the text_to_video response, take the
                                  'taskId' field. Repeatedly call
                                  check_video_status until the state is
                                  SUCCEEDED or FAILED.
                          STEP 4. On SUCCEEDED, publish a short summary
                                  message to the hub naming the taskId and
                                  'state=SUCCEEDED'. On FAILED, publish the
                                  failure_reason verbatim.

                        Do NOT call download_video_file — that is the file
                        collector's job. Do NOT generate a script — that is
                        the copywriter's job.
                        """)
                .maxIters(8)
                .build();
    }

    /**
     * Demo agent #3: file collector. Owns {@code PromoDemoTools.download_video_file}.
     * Reads the SUCCEEDED task from the hub and writes the .mp4 to the path
     * in the user message's metadata.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public ReActAgent fileCollectorAgent(RoutingChatModel routingChatModel,
                                          Toolkit fileCollectorToolkit) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("file-collector")
                .description("Downloads the completed promo video to the user's output path.")
                .model(routingChatModel)
                .toolkit(fileCollectorToolkit)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are the file collector in a 3-agent AI-promo team.

                        EXACT WORKFLOW:
                          STEP 1. Read the video producer agent's summary on
                                  the hub. Extract the 'taskId'.
                          STEP 2. Read the user message's metadata to find
                                  'saveTo' — that is where the .mp4 must be
                                  written.
                          STEP 3. Call download_video_file with:
                                  - task_id: the taskId from step 1.
                                  - save_to: the saveTo from step 2.
                          STEP 4. On success, reply in one short sentence
                                  naming the saved file path. On failure,
                                  surface the tool's error message verbatim.

                        Do NOT call text_to_video or check_video_status —
                        those are the video producer's tools. Do NOT
                        regenerate the script.
                        """)
                .maxIters(3)
                .build();
    }
}
