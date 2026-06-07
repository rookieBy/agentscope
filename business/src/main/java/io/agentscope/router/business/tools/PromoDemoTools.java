package io.agentscope.router.business.tools;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.router.business.demo.DemoProperties;
import io.agentscope.router.business.multimodal.MultimodalService;
import io.agentscope.router.business.multimodal.VideoFileDownloader;
import io.agentscope.router.business.multimodal.VideoTask;
import io.agentscope.router.business.multimodal.VideoTaskState;
import io.agentscope.router.llm.core.RoutingChatModel;
import io.agentscope.router.llm.provider.MiniMaxMultimodalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two demo-only {@code @Tool} methods used by the {@code AiPromoDemoService}
 * ReAct agent. They illustrate the two distinct ways a tool can wrap work:
 *
 * <ol>
 *   <li>{@link #writePromoCopy} — a tool that <b>wraps a sub-LLM call</b>.
 *       From the outside it looks like a normal function call, but internally
 *       it dispatches a fresh chat to {@link RoutingChatModel}, which itself
 *       runs TTFT-fallback routing across providers. The copy produced here
 *       is the "script" that the next tool feeds to the video API.</li>
 *   <li>{@link #downloadVideoFile} — a tool that <b>wraps a side-effectful
 *       external HTTP call</b>. It looks up the {@link VideoTask} in Redis,
 *       exchanges its {@code file_id} for a CDN download URL, then writes
 *       the bytes to the local path supplied by the caller. After this
 *       returns, the agent's task is done.</li>
 * </ol>
 *
 * <p><b>Threading / context.</b> Like {@code MediaTools}, tool methods here
 * receive a {@link ToolContext} parameter that the AgentScope framework
 * resolves via {@code RequestContextStore} (which reads
 * {@link MediaTools#currentRequest()}). The owning service
 * ({@code AiPromoDemoService}) must therefore call
 * {@link MediaTools#setCurrentRequest} in {@code doOnSubscribe} and clear
 * it in {@code doFinally} — otherwise {@code ctx.tenantId()} is null and
 * cross-tenant Redis lookups would fail.
 */
@Component
public class PromoDemoTools {

    private static final Logger log = LoggerFactory.getLogger(PromoDemoTools.class);

    private final RoutingChatModel routingChatModel;
    private final MultimodalService multimodal;
    private final MiniMaxMultimodalClient multimodalClient;
    private final VideoFileDownloader videoFileDownloader;
    private final DemoProperties demoProperties;

    public PromoDemoTools(RoutingChatModel routingChatModel,
                          MultimodalService multimodal,
                          MiniMaxMultimodalClient multimodalClient,
                          VideoFileDownloader videoFileDownloader,
                          DemoProperties demoProperties) {
        this.routingChatModel = routingChatModel;
        this.multimodal = multimodal;
        this.multimodalClient = multimodalClient;
        this.videoFileDownloader = videoFileDownloader;
        this.demoProperties = demoProperties;
    }

    // ---- tool #1: copywriter sub-agent (wraps a sub-LLM call) -----------

    /**
     * Generate a ~30-second promotional video script about {@code topic}.
     * The tool accepts "30 seconds" because that's what the end-user asked
     * for; the agent is told (via system prompt) to map it to a valid
     * {@code duration} ∈ {6, 10} for the video API in a subsequent call.
     *
     * <p>Implementation note: this is <b>not</b> a copy/paste from a
     * template. It calls the real {@link RoutingChatModel}, so the LLM
     * actually generates fresh text every time.
     */
    @Tool(name = "write_promo_copy",
          description = "Write a vivid, scene-by-scene English video script about the "
                  + "given topic. Output should be 3-5 scenes that together fill "
                  + "the requested duration. Use minimax-compatible camera "
                  + "commands in square brackets like [Push in], [Pan left], "
                  + "[Static shot] for camera control. Return only the script text, "
                  + "no preamble.")
    public Map<String, Object> writePromoCopy(
            @ToolParam(name = "topic", required = true,
                    description = "The subject of the promo, e.g. 'What AI can do for us'.")
            String topic,
            @ToolParam(name = "duration_seconds", required = true,
                    description = "How long the final video should feel, in seconds. "
                            + "The agent should map this to a valid video API value "
                            + "(6 or 10) before calling text_to_video.")
            Integer durationSeconds,
            @ToolParam(name = "language", required = false,
                    description = "Script language. Defaults to 'en'.")
            String language,
            ToolContext ctx) {

        log.info("[tool:write_promo_copy] tenant={} requestId={} topic='{}' duration={}s lang={}",
                ctx.tenantId(), ctx.requestId(), topic, durationSeconds,
                language == null ? "en" : language);

        String lang = (language == null || language.isBlank()) ? "en" : language;
        String sysPrompt = """
                You are an expert short-video copywriter.
                Produce a vivid, scene-by-scene script of EXACTLY 3 to 5 scenes that
                together feel like a %d-second promotional clip.
                - Output language: %s.
                - Each scene is one short paragraph (1-2 sentences).
                - Embed minimax-compatible camera commands in square brackets where
                  they add visual interest, e.g. [Push in], [Pan left], [Static shot],
                  [Zoom out]. Use at most one command per scene.
                - Return ONLY the script. No preamble, no closing line, no markdown.
                """.formatted(durationSeconds, lang);

        String userPrompt = "Topic: " + topic;

        List<Msg> msgs = List.of(
                Msg.builder().name("system").role(MsgRole.SYSTEM).textContent(sysPrompt).build(),
                Msg.builder().name("user").role(MsgRole.USER).textContent(userPrompt)
                        .metadata(Map.of(
                                "tenantId", ctx.tenantId(),
                                "requestId", ctx.requestId(),
                                "subAgent", "write_promo_copy"))
                        .build());

        // Block on the stream: we need the full text, not a token stream.
        String fullText = routingChatModel.stream(msgs, List.<ToolSchema>of(),
                        GenerateOptions.builder().build())
                .map(PromoDemoTools::extractText)
                .reduce("", (a, b) -> a + b)
                .block();

        log.info("[tool:write_promo_copy] script ready tenant={} requestId={} chars={}",
                ctx.tenantId(), ctx.requestId(),
                fullText == null ? 0 : fullText.length());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", fullText == null ? "" : fullText.trim());
        out.put("topic", topic);
        out.put("durationSeconds", durationSeconds);
        out.put("language", lang);
        return out;
    }

    // ---- tool #2: video file downloader (wraps HTTP + file IO) -----------

    /**
     * Download the completed video for a previously submitted task and save
     * it to {@code save_to} on the local filesystem. Resolves the task's
     * {@code file_id} via Redis (tenant-scoped), then calls
     * {@code /v1/files/retrieve} for a signed CDN URL, then writes the
     * bytes via {@link VideoFileDownloader}.
     */
    @Tool(name = "download_video_file",
          description = "Download a completed video to a local file path. "
                  + "Requires the task returned by text_to_video. Returns the "
                  + "absolute path and byte count of the saved file. "
                  + "If the task is not yet SUCCEEDED, returns a state field "
                  + "instead of writing a file.")
    public Map<String, Object> downloadVideoFile(
            @ToolParam(name = "task_id", required = true,
                    description = "The internal taskId returned by text_to_video.")
            String taskId,
            @ToolParam(name = "save_to", required = true,
                    description = "Absolute or working-dir-relative file path "
                            + "where the .mp4 should be written, including the "
                            + "filename. Parent directories will be created.")
            String saveTo,
            ToolContext ctx) {

        log.info("[tool:download_video_file] tenant={} requestId={} taskId={} saveTo={}",
                ctx.tenantId(), ctx.requestId(), taskId, saveTo);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("saveTo", saveTo);

        VideoTask task = multimodal.getTask(ctx.tenantId(), taskId).orElse(null);
        if (task == null) {
            out.put("ok", false);
            out.put("message", "No such video task for this tenant");
            return out;
        }
        if (task.state() != VideoTaskState.SUCCEEDED) {
            out.put("ok", false);
            out.put("state", task.state().name());
            out.put("message", "Task not yet SUCCEEDED; cannot download.");
            return out;
        }
        String fileId = task.fileId();
        if (fileId == null || fileId.isBlank()) {
            out.put("ok", false);
            out.put("state", task.state().name());
            out.put("message", "Task SUCCEEDED but no file_id on record.");
            return out;
        }

        String downloadUrl = multimodalClient.retrieveFileDownloadUrl(fileId).block();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            out.put("ok", false);
            out.put("message", "Could not resolve download URL for file_id=" + fileId);
            return out;
        }
        out.put("downloadUrl", downloadUrl);

        Path dest = Paths.get(saveTo);
        Path saved = videoFileDownloader.download(downloadUrl, dest).block();
        long bytes = saved == null ? 0L : saved.toFile().length();
        log.info("[tool:download_video_file] saved tenant={} requestId={} path={} bytes={}",
                ctx.tenantId(), ctx.requestId(), saved, bytes);

        out.put("ok", true);
        out.put("path", saved == null ? null : saved.toString());
        out.put("bytes", bytes);
        return out;
    }

    private static String extractText(ChatResponse cr) {
        if (cr == null) return "";
        var blocks = cr.getContent();
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var b : blocks) {
            if (b instanceof TextBlock tb) {
                String t = tb.getText();
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }

    // Used by AiPromoDemoService to build a deterministic save path from yml.
    public DemoProperties demoProperties() {
        return demoProperties;
    }
}
