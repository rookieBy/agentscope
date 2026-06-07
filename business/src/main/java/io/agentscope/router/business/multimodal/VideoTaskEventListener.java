package io.agentscope.router.business.multimodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.router.common.tenant.RedisKeyFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the {@link RedisMessageListenerContainer} that subscribes to per-task
 * video-event channels and fans events out to per-task Reactor sinks consumed
 * by {@code MediaController}'s SSE endpoint.
 *
 * <p><b>Why a single global container + per-task sinks.</b> A Redis
 * subscriber is cheap to add but each one still costs a connection on the
 * Lettuce pool. The container itself is started once at bean creation; for
 * every {@link #subscribe(String, String)} call we register a brand-new
 * {@link MessageListener} against the global container, scoped to the
 * specific task's channel via a programmatic subscription. The listener
 * unmaps itself when the first terminal state is observed so the SSE stream
 * closes and the connection is released.
 */
@Component
public class VideoTaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(VideoTaskEventListener.class);

    private final RedisMessageListenerContainer container;
    private final ObjectMapper mapper;
    /** Active per-task subscriptions; removed when the SSE stream completes. */
    private final List<ActiveSub> active = new CopyOnWriteArrayList<>();

    public VideoTaskEventListener(RedisMessageListenerContainer container, ObjectMapper mapper) {
        this.container = container;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        if (!container.isRunning()) container.start();
        log.info("VideoTaskEventListener started (container running={})", container.isRunning());
    }

    @PreDestroy
    void stop() {
        try { container.stop(); } catch (Exception ignored) {}
    }

    /**
     * Subscribe to state changes for one task. Emits the current task snapshot
     * first (cold signal), then every subsequent change. The stream completes
     * automatically when the task reaches SUCCEEDED / FAILED.
     *
     * <p>If no task exists for the given id (cross-tenant probe, expired
     * hash, etc.) the stream emits a single {@link VideoTaskManager.VideoTaskEvent}
     * with {@code type = "NOT_FOUND"} and completes — the SSE handler turns
     * that into a 404.
     */
    public Flux<VideoTaskManager.VideoTaskEvent> subscribe(String tenantId, String taskId,
                                                          VideoTaskManager manager) {
        Sinks.Many<VideoTaskManager.VideoTaskEvent> sink =
                Sinks.many().unicast().onBackpressureBuffer();
        String channel = RedisKeyFactory.videoEventChannel(tenantId, taskId);

        MessageListener listener = (msg, pattern) -> {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            try {
                VideoTaskManager.VideoTaskEvent ev = mapper.readValue(body,
                        VideoTaskManager.VideoTaskEvent.class);
                sink.tryEmitNext(ev);
                if (ev.task() != null && ev.task().state().isTerminal()) {
                    sink.tryEmitComplete();
                }
            } catch (Exception parseErr) {
                log.warn("video.event.parse_error channel={} cause={}",
                        channel, parseErr.getClass().getSimpleName());
            }
        };

        // Register against the global container. Stash the subscription so
        // we can release it when the downstream cancels.
        container.addMessageListener(listener, new PatternTopic(channel));
        ActiveSub asub = new ActiveSub(channel, listener, sink);
        active.add(asub);

        // Cold signal: emit the current snapshot first (or NOT_FOUND),
        // then forward live events. Single Flux so the controller can just
        // map each event to an SSE chunk.
        return Flux.<VideoTaskManager.VideoTaskEvent>create(emitter -> {
            Optional<VideoTask> current = manager.find(tenantId, taskId);
            if (current.isPresent()) {
                emitter.next(VideoTaskManager.VideoTaskEvent.of(current.get()));
            } else {
                emitter.next(new VideoTaskManager.VideoTaskEvent("NOT_FOUND", null));
                emitter.complete();
                release(asub);
                return;
            }
            sink.asFlux()
                    .doOnCancel(() -> release(asub))
                    .doOnTerminate(() -> release(asub))
                    .subscribe(
                            emitter::next,
                            emitter::error,
                            emitter::complete);
        });
    }

    private void release(ActiveSub asub) {
        try { container.removeMessageListener(asub.listener); } catch (Exception ignored) {}
        active.remove(asub);
    }

    private record ActiveSub(String channel,
                              MessageListener listener,
                              Sinks.Many<VideoTaskManager.VideoTaskEvent> sink) {}
}
