package io.agentscope.router.business.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.router.business.demo.DemoProperties;
import io.agentscope.router.business.multimodal.MultimodalService;
import io.agentscope.router.business.multimodal.VideoFileDownloader;
import io.agentscope.router.business.multimodal.VideoTask;
import io.agentscope.router.business.multimodal.VideoTaskState;
import io.agentscope.router.llm.provider.MiniMaxMultimodalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo-only {@code @Tool} bean used by the {@code fileCollectorAgent} in the
 * AI-promo multi-agent demo.
 *
 * <p>This class used to host two tools:
 * <ol>
 *   <li>{@code write_promo_copy} — a sub-LLM call wrapped in a tool. Removed
 *       in the multi-agent refactor: copy generation is now done by the
 *       dedicated {@code copywriterAgent} ReAct agent, which is a first-class
 *       participant in the {@code MsgHub}, not a tool call.</li>
 *   <li>{@code download_video_file} — a side-effectful external HTTP + file
 *       IO call. Still present; consumed by {@code fileCollectorAgent}.</li>
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

    private final MultimodalService multimodal;
    private final MiniMaxMultimodalClient multimodalClient;
    private final VideoFileDownloader videoFileDownloader;
    private final DemoProperties demoProperties;

    public PromoDemoTools(MultimodalService multimodal,
                          MiniMaxMultimodalClient multimodalClient,
                          VideoFileDownloader videoFileDownloader,
                          DemoProperties demoProperties) {
        this.multimodal = multimodal;
        this.multimodalClient = multimodalClient;
        this.videoFileDownloader = videoFileDownloader;
        this.demoProperties = demoProperties;
    }

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

    // Used by AiPromoDemoService to build a deterministic save path from yml.
    public DemoProperties demoProperties() {
        return demoProperties;
    }
}
