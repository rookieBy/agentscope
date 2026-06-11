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
 * AI-music demo.
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
 * <p><b>Demo path</b> ({@code /api/v1/demo/ai-music}) uses THREE independent
 * ReAct agents, again coordinated by a {@code MsgHub}:
 * <ul>
 *   <li>{@code copywriterAgent} — pure-LLM, no tools. Generates the lyrics.</li>
 *   <li>{@code musicProducerAgent} — owns {@code MediaTools.text_to_music}.</li>
 *   <li>{@code fileCollectorAgent} — owns {@code PromoDemoTools.download_music_file}.</li>
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
                        advisor's job. Do NOT call text_to_image, text_to_music,
                        or any media-generation tools — those are not part of
                        routing.
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
     * Demo toolkit for the music producer agent. Registers {@link MediaTools}
     * (its {@code text_to_music} method). Gated on
     * {@code agentscope.demo.ai-promo.enabled=true}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public Toolkit musicProducerToolkit(MediaTools mediaTools) {
        ToolkitConfig config = ToolkitConfig.builder().build();
        Toolkit toolkit = new Toolkit(config);
        toolkit.registerTool(mediaTools);
        log.info("Registered musicProducer toolkit with {} tool(s): {}",
                toolkit.getToolNames().size(), toolkit.getToolNames());
        return toolkit;
    }

    /**
     * Demo toolkit for the file collector agent. Registers {@link PromoDemoTools}
     * (its {@code download_music_file} method). Gated on
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
     * Demo agent #1: copywriter. Pure LLM, no tools. Generates the lyrics
     * that the next agent in the hub will hand to the music API.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public ReActAgent copywriterAgent(RoutingChatModel routingChatModel) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("copywriter")
                .description("Writes structured lyrics with [verse]/[chorus] section tags.")
                .model(routingChatModel)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are a songwriter for short music promos. You will
                        receive a topic and a target duration in seconds from
                        the user. Produce structured lyrics in the user's
                        original language with explicit section tags: [verse],
                        [chorus], optional [bridge].

                        Keep total length under 800 characters. Make the lyrics
                        rhyme where natural, but prioritize keeping the message
                        on-topic.

                        Do NOT call any tool. Output ONLY the lyrics, no
                        commentary or explanation. The next agent will pick
                        up your lyrics from the message hub.
                        """)
                .maxIters(2)
                .build();
    }

    /**
     * Demo agent #2: music producer. Owns {@code MediaTools.text_to_music}.
     * Reads the lyrics published by {@code copywriterAgent} on the hub,
     * calls the synchronous music-2.6-free API, and broadcasts the resulting
     * tempPath to the hub.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.demo.ai-promo", name = "enabled", havingValue = "true")
    public ReActAgent musicProducerAgent(RoutingChatModel routingChatModel,
                                          Toolkit musicProducerToolkit) {
        ToolExecutionContext agentToolContext = ToolExecutionContext.builder()
                .addStore(new RequestContextStore())
                .build();
        return ReActAgent.builder()
                .name("music-producer")
                .description("Generates a music clip from structured lyrics via text_to_music.")
                .model(routingChatModel)
                .toolkit(musicProducerToolkit)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are a music producer in a 3-agent music-promo pipeline.

                        You will see structured lyrics from the copywriter on
                        the shared message hub. Your job is to call the
                        `text_to_music` tool EXACTLY ONCE with those lyrics.

                        Tool: text_to_music(lyrics: string, prompt: string optional, model: string optional)
                          - Pass the full lyrics text into `lyrics`.
                          - Set `prompt` to a short English style / genre /
                            mood description that fits the lyrics topic.
                            Example: for a World Cup France team promo, use
                            "upbeat pop, celebratory, ~10 seconds, energetic
                            vocals". The `prompt` is REQUIRED in spirit (the
                            provider's music-2.6+ endpoint refuses requests
                            without it), so always fill it in with something
                            sensible rather than leaving it blank.
                          - Leave `model` empty to use the default
                            music-2.6-free.
                          - The tool returns a map with `tempPath` (absolute
                            path to the generated MP3).

                        After the tool returns successfully, post EXACTLY this
                        message to the hub:
                          MUSIC_READY: <tempPath>

                        Where <tempPath> is the value of the `tempPath` field
                        from the tool response.

                        Then stop. Do NOT retry on success. Do NOT poll. Do
                        NOT call any other tool. If the tool fails, surface
                        the error message and stop.
                        """)
                .maxIters(3)
                .build();
    }

    /**
     * Demo agent #3: file collector. Owns {@code PromoDemoTools.download_music_file}.
     * Reads the {@code MUSIC_READY: <tempPath>} message from the hub and
     * copies the temp MP3 to the user-visible save path announced in the
     * task brief.
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
                .description("Copies the generated music file from temp dir to the user's output path.")
                .model(routingChatModel)
                .toolkit(fileCollectorToolkit)
                .toolExecutionContext(agentToolContext)
                .sysPrompt("""
                        You are a file collector for the music-promo pipeline.

                        You watch the shared message hub. When you see a
                        message starting with "MUSIC_READY: ", extract the
                        absolute path that follows.

                        Then call `download_music_file(audioPath, saveTo)`:
                          - audioPath = the path extracted from the MUSIC_READY
                            message.
                          - saveTo = the absolute save path provided in your
                            task brief (look for a line like
                            "Save the final music to: <path>").

                        When the tool returns the saved path, post EXACTLY
                        this message to the hub:
                          SAVED: <returned-path>

                        Then stop. Do NOT call any other tool.
                        """)
                .maxIters(3)
                .build();
    }
}
