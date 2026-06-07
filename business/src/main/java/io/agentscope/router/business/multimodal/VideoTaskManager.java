package io.agentscope.router.business.multimodal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.router.common.constants.Constants;
import io.agentscope.router.common.tenant.RedisKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists and publishes {@link VideoTask}s. One bean shared by:
 * <ul>
 *   <li>{@code MultimodalService} — writes the initial PENDING state and the
 *       post-submit QUEUED state,</li>
 *   <li>{@code VideoTaskWorker} — updates the state and URL on each poll,</li>
 *   <li>{@code MediaController} — reads state for the HTTP path and subscribes
 *       for the SSE path (via {@code VideoTaskEventListener}),</li>
 *   <li>{@code MediaTools.check_video_status} — reads the latest state.</li>
 * </ul>
 *
 * <p><b>Layout</b> (always built via {@link RedisKeyFactory}, never hard-coded):
 * <ul>
 *   <li>Hash: {@code t:{tenantId}:video:task:{taskId}} — TTL 24h</li>
 *   <li>Pub/Sub channel: {@code t:{tenantId}:video:event:{taskId}}</li>
 * </ul>
 *
 * <p>Concurrency: a single {@link #save(VideoTask)} call uses Redis
 * {@code HSET} (atomic per field), followed by {@code EXPIRE} (refreshed
 * every write). Subscribers receive the {@link VideoTaskEvent} as a JSON
 * payload on the per-task channel. The hot subscribe path is owned by
 * {@link VideoTaskEventListener} so the underlying
 * {@code RedisMessageListenerContainer} lifecycle stays Spring-managed.
 */
@Service
public class VideoTaskManager {

    private static final Logger log = LoggerFactory.getLogger(VideoTaskManager.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public VideoTaskManager(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = Objects.requireNonNull(redis);
        this.mapper = Objects.requireNonNull(mapper);
        this.ttl = Duration.ofHours(Constants.DEFAULT_VIDEO_TTL_HOURS);
    }

    // ---- CRUD ------------------------------------------------------------

    /** Persist a task. Replaces all fields; refreshes the 24h TTL. */
    public void save(VideoTask task) {
        String key = RedisKeyFactory.videoTask(task.tenantId(), task.taskId());
        Map<String, String> hash = toHash(task);
        redis.opsForHash().putAll(key, hash);
        redis.expire(key, ttl);
    }

    /** Read a task. Returns empty when the key is missing or the tenant is wrong. */
    public Optional<VideoTask> find(String tenantId, String taskId) {
        String key = RedisKeyFactory.videoTask(tenantId, taskId);
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        if (raw.isEmpty()) return Optional.empty();
        return Optional.of(fromHash(raw));
    }

    /** Cheap existence check (used by cross-tenant isolation tests). */
    public boolean exists(String tenantId, String taskId) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeyFactory.videoTask(tenantId, taskId)));
    }

    /** List all tasks for one tenant (oldest first). Used by the worker. */
    public List<VideoTask> listForTenant(String tenantId) {
        RedisKeyFactory.requireTenantId(tenantId);
        String pattern = "t:" + tenantId + ":video:task:*";
        List<VideoTask> out = new ArrayList<>();
        var keys = redis.keys(pattern);
        for (String key : keys) {
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            if (!raw.isEmpty()) out.add(fromHash(raw));
        }
        return out;
    }

    /**
     * Discover all tenants that currently have at least one video-task key.
     * Used by {@link VideoTaskWorker} to know which tenants to poll on each
     * sweep. Implementation: a single {@code SCAN MATCH t:*:video:task:*}
     * pass, then dedupe by the {@code t:{tenant}:...} prefix.
     *
     * <p>This is O(N) over the cluster's video-task keys; acceptable for
     * the smoke-test traffic. A production deployment would maintain the
     * tenant set in-memory and refresh on a much longer interval.
     */
    public java.util.Set<String> listTenantsWithOpenTasks() {
        java.util.Set<String> tenants = new java.util.HashSet<>();
        var keys = redis.keys("t:*:video:task:*");
        for (String key : keys) {
            String[] parts = key.split(":");
            // Layout: ["t", "{tenantId}", "video", "task", "{taskId}"]
            if (parts.length >= 2) {
                tenants.add(parts[1]);
            }
        }
        return tenants;
    }

    /**
     * Idempotent state transition. Saves the new task object and, if the
     * state actually changed, publishes a {@link VideoTaskEvent} on the
     * per-task channel so SSE subscribers can react.
     */
    public VideoTask transition(VideoTask newState) {
        VideoTask previous = find(newState.tenantId(), newState.taskId()).orElse(null);
        save(newState);
        if (previous == null || previous.state() != newState.state()
                || !Objects.equals(previous.videoUrl(), newState.videoUrl())) {
            publish(newState);
        }
        return newState;
    }

    /** Publish a state change (also used directly by the SSE listener). */
    public void publish(VideoTask task) {
        String channel = RedisKeyFactory.videoEventChannel(task.tenantId(), task.taskId());
        try {
            String body = mapper.writeValueAsString(VideoTaskEvent.of(task));
            redis.convertAndSend(channel, body);
        } catch (JsonProcessingException e) {
            log.warn("video.event.serialize_error tenant={} taskId={} cause={}",
                    task.tenantId(), task.taskId(), e.getClass().getSimpleName());
        }
    }

    public ObjectMapper mapper() { return mapper; }

    // ---- Jackson / hash encoding ----------------------------------------

    private Map<String, String> toHash(VideoTask t) {
        return Map.ofEntries(
                Map.entry("taskId", t.taskId()),
                Map.entry("tenantId", t.tenantId()),
                Map.entry("prompt", t.prompt()),
                Map.entry("model", t.model() == null ? "" : t.model()),
                Map.entry("duration", t.duration() == null ? "" : t.duration().toString()),
                Map.entry("resolution", t.resolution() == null ? "" : t.resolution()),
                Map.entry("firstFrameImageUrl", t.firstFrameImageUrl() == null ? "" : t.firstFrameImageUrl()),
                Map.entry("state", t.state().name()),
                Map.entry("providerTaskId", t.providerTaskId() == null ? "" : t.providerTaskId()),
                Map.entry("fileId", t.fileId() == null ? "" : t.fileId()),
                Map.entry("videoUrl", t.videoUrl() == null ? "" : t.videoUrl()),
                Map.entry("failureReason", t.failureReason() == null ? "" : t.failureReason()),
                Map.entry("createdAt", t.createdAt().toString()),
                Map.entry("updatedAt", t.updatedAt().toString())
        );
    }

    private VideoTask fromHash(Map<Object, Object> raw) {
        return new VideoTask(
                str(raw, "taskId"),
                str(raw, "tenantId"),
                str(raw, "prompt"),
                nullIfBlank(str(raw, "model")),
                intOrNull(raw, "duration"),
                nullIfBlank(str(raw, "resolution")),
                nullIfBlank(str(raw, "firstFrameImageUrl")),
                VideoTaskState.valueOf(str(raw, "state")),
                nullIfBlank(str(raw, "providerTaskId")),
                nullIfBlank(str(raw, "fileId")),
                nullIfBlank(str(raw, "videoUrl")),
                nullIfBlank(str(raw, "failureReason")),
                java.time.Instant.parse(str(raw, "createdAt")),
                java.time.Instant.parse(str(raw, "updatedAt"))
        );
    }

    private static String str(Map<Object, Object> raw, String f) {
        Object v = raw.get(f);
        return v == null ? "" : v.toString();
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Integer intOrNull(Map<Object, Object> raw, String f) {
        String s = str(raw, f);
        if (s.isBlank()) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    // ---- inner types ----------------------------------------------------

    /**
     * SSE-friendly event payload. {@code type} mirrors {@link VideoTaskState}
     * (e.g. {@code "RUNNING"}, {@code "SUCCEEDED"}), plus the full task object
     * so subscribers can render a progress pill without a follow-up GET.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VideoTaskEvent(String type, VideoTask task) {
        public static VideoTaskEvent of(VideoTask t) {
            return new VideoTaskEvent(t.state().name(), t);
        }
    }
}
