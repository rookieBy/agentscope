package io.agentscope.router.api.controller;

import io.agentscope.router.api.dto.MediaRequest;
import io.agentscope.router.business.multimodal.MultimodalService;
import io.agentscope.router.business.multimodal.VideoTask;
import io.agentscope.router.business.multimodal.VideoTaskEventListener;
import io.agentscope.router.business.multimodal.VideoTaskManager;
import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;
import io.agentscope.router.common.tenant.TenantContextHolder;
import io.agentscope.router.common.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Direct multimodal HTTP endpoints. These exist alongside the {@code @Tool}-
 * annotated methods on {@code MediaTools} so callers (or frontends that do
 * not want to pay the LLM-token round-trip) can hit the multimodal service
 * without going through the agent.
 *
 * <p>All routes live under {@code /api/v1/media} and inherit tenant
 * isolation from {@link TenantContextHolder} (set by
 * {@code TenantContextFilter} from the {@code X-Tenant-Id} header).
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final MultimodalService multimodal;
    private final VideoTaskManager videoTaskManager;
    private final VideoTaskEventListener videoEventListener;

    public MediaController(MultimodalService multimodal,
                           VideoTaskManager videoTaskManager,
                           VideoTaskEventListener videoEventListener) {
        this.multimodal = multimodal;
        this.videoTaskManager = videoTaskManager;
        this.videoEventListener = videoEventListener;
    }

    // ---- image (sync) ---------------------------------------------------

    @PostMapping(value = "/image", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> generateImage(@RequestBody(required = false) MediaRequest body) {
        String tenantId = requireTenant();
        if (body == null || body.prompt().isBlank()) {
            return Mono.error(new BizException(ErrorCode.INVALID_TENANT_ID,
                    "Request body must contain a non-empty 'prompt' field"));
        }
        log.info("media.image tenant={} prompt.len={} aspect={} n={}",
                tenantId, body.prompt().length(), body.aspectRatio(), body.n());

        return multimodal.generateImage(tenantId, body.prompt(), body.aspectRatio(), body.n())
                .map(r -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("model", r.model());
                    out.put("imageUrls", r.imageUrls());
                    return out;
                });
    }

    // ---- video (async) --------------------------------------------------

    @PostMapping(value = "/video", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> submitVideo(@RequestBody(required = false) MediaRequest body) {
        String tenantId = requireTenant();
        if (body == null || body.prompt().isBlank()) {
            return Mono.error(new BizException(ErrorCode.INVALID_TENANT_ID,
                    "Request body must contain a non-empty 'prompt' field"));
        }
        log.info("media.video.submit tenant={} prompt.len={} duration={} resolution={} firstFrame={}",
                tenantId, body.prompt().length(), body.duration(), body.resolution(),
                body.firstFrameImageUrl().isBlank() ? "no" : "yes");

        return multimodal.submitVideoTask(tenantId, body.prompt(),
                        body.duration(), body.resolution(), body.firstFrameImageUrl())
                .map(task -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("taskId", task.taskId());
                    out.put("state", task.state().name());
                    out.put("model", task.model());
                    out.put("createdAt", task.createdAt().toString());
                    return out;
                });
    }

    /**
     * Read the current snapshot of a previously submitted video task. Returns
     * 404 ({@link ErrorCode#VIDEO_TASK_NOT_FOUND}) when the task does not
     * exist for the caller's tenant — never bleeds existence across tenants.
     */
    @GetMapping(value = "/video/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getVideo(@PathVariable String taskId) {
        String tenantId = requireTenant();
        requireNonBlankTaskId(taskId);
        log.info("media.video.get tenant={} taskId={}", tenantId, taskId);

        return videoTaskManager.find(tenantId, taskId)
                .<Map<String, Object>>map(this::toVideoSnapshot)
                .orElseThrow(() -> new BizException(ErrorCode.VIDEO_TASK_NOT_FOUND,
                        "No video task " + taskId + " for tenant " + tenantId));
    }

    /**
     * SSE stream of state changes for a video task. The first event is the
     * current snapshot (or a {@code NOT_FOUND} marker if the task does not
     * exist); subsequent events are published by the worker each time the
     * polled state changes. The stream auto-completes when the task reaches
     * {@code SUCCEEDED} or {@code FAILED}.
     */
    @GetMapping(value = "/video/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamVideo(@PathVariable String taskId) {
        String tenantId = requireTenant();
        requireNonBlankTaskId(taskId);
        log.info("media.video.stream tenant={} taskId={}", tenantId, taskId);

        return videoEventListener.subscribe(tenantId, taskId, videoTaskManager)
                .map(this::toSseChunk)
                .doOnError(err -> log.warn("media.video.stream error tenant={} taskId={} cause={}",
                        tenantId, taskId, err.getClass().getSimpleName()));
    }

    // ---- helpers --------------------------------------------------------

    private String requireTenant() {
        String tenantId = TenantContextHolder.currentTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.MISSING_TENANT_ID);
        }
        return tenantId;
    }

    private static void requireNonBlankTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
    }

    private Map<String, Object> toVideoSnapshot(VideoTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.taskId());
        m.put("state", t.state().name());
        m.put("model", t.model());
        m.put("videoUrl", t.videoUrl());
        m.put("fileId", t.fileId());
        m.put("failureReason", t.failureReason());
        m.put("createdAt", t.createdAt().toString());
        m.put("updatedAt", t.updatedAt().toString());
        return m;
    }

    private String toSseChunk(VideoTaskManager.VideoTaskEvent ev) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", ev.type());
        if (ev.task() != null) {
            payload.put("task", toVideoSnapshot(ev.task()));
        } else if ("NOT_FOUND".equals(ev.type())) {
            payload.put("message", "No such video task for this tenant");
        }
        return "data: " + JsonUtils.toJson(payload) + "\n\n";
    }
}
