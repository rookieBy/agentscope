package io.agentscope.router.business.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.router.business.multimodal.MultimodalService;
import io.agentscope.router.business.multimodal.VideoTask;
import io.agentscope.router.llm.core.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring-managed bean holding every {@code @Tool}-annotated method exposed
 * to the ReAct agent. The {@link io.agentscope.core.tool.Toolkit} scans this
 * bean at startup and turns each public method into an LLM-callable tool.
 *
 * <p><b>Per-request context.</b> Each tool method declares a {@link ToolContext}
 * parameter (no {@code @ToolParam} annotation). AgentScope's
 * {@code ToolMethodInvoker} resolves that parameter by calling
 * {@code ToolExecutionContext.get(ToolContext.class)}; the
 * {@link RequestContextStore} we register on the agent's
 * {@code toolExecutionContext} returns the current request's {@code ToolContext}
 * from a process-wide {@link AtomicReference} that
 * {@code ChatAgentService} sets in {@code doOnSubscribe} and clears in
 * {@code doFinally}.
 */
@Component
public class MediaTools {

    private static final Logger log = LoggerFactory.getLogger(MediaTools.class);

    /**
     * Active request context for the currently-running agent stream. Set by
     * {@code ChatAgentService} when a stream is subscribed; cleared when the
     * stream completes. Read by {@link RequestContextStore} at tool-invocation
     * time so tool methods can be parameter-injected by AgentScope with the
     * right {@code tenantId}.
     */
    private static final AtomicReference<ToolContext> CURRENT = new AtomicReference<>();

    private final ModelRegistry modelRegistry;
    private final MultimodalService multimodal;

    public MediaTools(ModelRegistry modelRegistry, MultimodalService multimodal) {
        this.modelRegistry = modelRegistry;
        this.multimodal = multimodal;
    }

    /** Set by the calling service for the duration of one agent invocation. */
    public static void setCurrentRequest(ToolContext ctx) {
        CURRENT.set(ctx);
    }

    public static void clearCurrentRequest() {
        CURRENT.set(null);
    }

    /** Read by {@link RequestContextStore} to inject {@code ToolContext} into tool methods. */
    public static ToolContext currentRequest() {
        return CURRENT.get();
    }

    // ---- LLM introspection ---------------------------------------------

    /**
     * Lists the LLM models currently registered and available to the caller's
     * tenant. The agent uses this to discover its options before delegating
     * to a specific provider.
     */
    @Tool(name = "list_available_models",
          description = "List all LLM models currently registered in the router, "
                  + "with their provider id and qualified name. Use this to discover "
                  + "which models you can call.")
    public List<ModelInfo> listAvailableModels(ToolContext ctx) {
        log.info("tool.listAvailableModels tenant={} requestId={}", ctx.tenantId(), ctx.requestId());
        return modelRegistry.all().stream()
                .map(a -> new ModelInfo(a.provider(), a.qualifiedName()))
                .toList();
    }

    /**
     * Returns the current health snapshot for a single model: TTFT EMA, error
     * rate EMA, consecutive-failure count, and cooldown remaining. Useful for
     * the agent (or a caller) to decide which model to route to next.
     */
    @Tool(name = "model_health",
          description = "Get the health snapshot for a specific model. "
                  + "Returns ttftMs (exponential moving average of time-to-first-token), "
                  + "errorRate, consecutiveFailures and cooldownRemainingMs.")
    public Map<String, Object> modelHealth(
            @ToolParam(name = "qualified_name",
                       required = true,
                       description = "The qualified model name, e.g. 'minimax:MiniMax-M3'.")
            String qualifiedName,
            ToolContext ctx) {
        log.info("tool.modelHealth tenant={} requestId={} model={}",
                ctx.tenantId(), ctx.requestId(), qualifiedName);
        var maybe = modelRegistry.find(qualifiedName);
        if (maybe.isEmpty()) {
            return Map.of(
                    "qualifiedName", qualifiedName,
                    "found", false,
                    "message", "No model registered with that name");
        }
        var snap = maybe.get().health(ctx.tenantId());
        long now = System.currentTimeMillis();
        long cooldownRemaining = Math.max(0L, snap.cooldownUntil() - now);
        return Map.of(
                "qualifiedName", qualifiedName,
                "found", true,
                "ttftMs", snap.ttftEmaMs(),
                "errorRate", snap.errorRateEma(),
                "consecutiveFailures", snap.consecutiveFailures(),
                "cooldownRemainingMs", cooldownRemaining);
    }

    // ---- multimodal (image / video) ------------------------------------

    /**
     * Generate one or more images from a text prompt. Returns a JSON-friendly
     * map with the model name and a list of image URLs.
     */
    @Tool(name = "text_to_image",
          description = "Generate one or more images from a text prompt using the "
                  + "registered image model. Returns a JSON object with 'imageUrls' "
                  + "(list of URL strings) and 'model' (the model used).")
    public Map<String, Object> textToImage(
            @ToolParam(name = "prompt", required = true,
                    description = "A short English description of the image.")
            String prompt,
            @ToolParam(name = "aspect_ratio", required = false,
                    description = "Optional aspect ratio. One of 1:1 (default), 16:9, 4:3, 3:2, 2:3, 3:4, 9:16, 21:9.")
            String aspectRatio,
            @ToolParam(name = "n", required = false,
                    description = "Optional number of images, 1..9, default 1.")
            Integer n,
            ToolContext ctx) {
        log.info("tool.textToImage tenant={} requestId={} prompt.len={} aspect={} n={}",
                ctx.tenantId(), ctx.requestId(),
                prompt == null ? 0 : prompt.length(), aspectRatio, n);
        MultimodalService.ImageResult r = multimodal.generateImage(
                ctx.tenantId(), prompt, aspectRatio, n).block();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", r.model());
        out.put("imageUrls", r.imageUrls());
        return out;
    }

    /**
     * Submit an async text-to-video task. Returns the internal {@code taskId}
     * (UUID); callers can poll with {@link #checkVideoStatus} or subscribe to
     * the SSE stream on {@code GET /api/v1/media/video/{taskId}/stream}.
     */
    @Tool(name = "text_to_video",
          description = "Submit an asynchronous text-to-video task. Returns a JSON object "
                  + "with 'taskId' (poll with check_video_status) and 'state' (initial PENDING, "
                  + "transitions to QUEUED -> RUNNING -> SUCCEEDED or FAILED).")
    public Map<String, Object> textToVideo(
            @ToolParam(name = "prompt", required = true,
                    description = "A short English description of the video.")
            String prompt,
            @ToolParam(name = "duration", required = false,
                    description = "Optional duration in seconds (e.g. 6 or 10).")
            Integer duration,
            @ToolParam(name = "resolution", required = false,
                    description = "Optional resolution (e.g. 768P or 1080P).")
            String resolution,
            ToolContext ctx) {
        log.info("tool.textToVideo tenant={} requestId={} prompt.len={} duration={} resolution={}",
                ctx.tenantId(), ctx.requestId(),
                prompt == null ? 0 : prompt.length(), duration, resolution);
        VideoTask task = multimodal.submitVideoTask(
                ctx.tenantId(), prompt, duration, resolution, null).block();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.taskId());
        out.put("state", task.state().name());
        out.put("model", task.model());
        out.put("createdAt", task.createdAt().toString());
        return out;
    }

    /**
     * Look up the current state of a previously submitted video task.
     * Cross-tenant probes return a {@code found:false} payload rather than
     * 404 to keep the LLM flow free of error handling.
     */
    @Tool(name = "check_video_status",
          description = "Look up the current state of a video task by its taskId. "
                  + "Returns state, videoUrl (when SUCCEEDED) and failureReason (when FAILED).")
    public Map<String, Object> checkVideoStatus(
            @ToolParam(name = "task_id", required = true,
                    description = "The internal taskId returned by text_to_video.")
            String taskId,
            ToolContext ctx) {
        log.info("tool.checkVideoStatus tenant={} requestId={} taskId={}",
                ctx.tenantId(), ctx.requestId(), taskId);
        return multimodal.getTask(ctx.tenantId(), taskId)
                .<Map<String, Object>>map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("found", true);
                    m.put("taskId", t.taskId());
                    m.put("state", t.state().name());
                    m.put("videoUrl", t.videoUrl());
                    m.put("fileId", t.fileId());
                    m.put("providerTaskId", t.providerTaskId());
                    m.put("failureReason", t.failureReason());
                    m.put("createdAt", t.createdAt().toString());
                    m.put("updatedAt", t.updatedAt().toString());
                    return m;
                })
                .orElseGet(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("found", false);
                    m.put("taskId", taskId);
                    m.put("message", "No such video task for this tenant");
                    return m;
                });
    }

    /** DTO for {@link #listAvailableModels}. Public so the AgentScope schema
     *  generator can introspect the return type. */
    public record ModelInfo(String provider, String qualifiedName) {}
}
