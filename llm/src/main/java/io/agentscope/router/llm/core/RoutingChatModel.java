package io.agentscope.router.llm.core;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import io.agentscope.router.llm.routing.HealthScoreService;
import io.agentscope.router.common.tenant.TenantContextHolder;
import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;
import io.agentscope.router.llm.config.RoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routing decorator over the {@link ModelRegistry}. For each call it:
 * <ol>
 *   <li>asks {@link HealthScoreService} to pick the best healthy model</li>
 *   <li>opens a stream against that model</li>
 *   <li>runs a TTFT timer — if no first token arrives within
 *       {@code agentscope.routing.ttft-timeout-ms}, cancels the candidate and
 *       recurses with the next-best one</li>
 *   <li>records success (with observed TTFT) on first token + completion,
 *       failure on error or TTFT timeout</li>
 * </ol>
 *
 * <p>Implementation note: uses {@link Flux#create} so we can swap the active
 * upstream when we switch candidates mid-stream. The outer sink lives for the
 * full request; we just keep cancelling/replacing the inner subscription.
 */
public class RoutingChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);

    private final ModelRegistry registry;
    private final HealthScoreService health;
    private final RoutingProperties props;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "routing-ttft");
                t.setDaemon(true);
                return t;
            });

    public RoutingChatModel(ModelRegistry registry, HealthScoreService health, RoutingProperties props) {
        this.registry = registry;
        this.health = health;
        this.props = props;
    }

    @Override
    public String getModelName() {
        return "routing:" + (registry.isEmpty() ? "empty" : String.join(",", registry.qualifiedNames()));
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String tenantId = resolveTenantId(messages);
        if (tenantId == null) {
            return Flux.error(new BizException(ErrorCode.MISSING_TENANT_ID,
                    "RoutingChatModel requires a tenant id: bind TenantContext (X-Tenant-Id header) "
                            + "or pass tenantId in the last user message's metadata."));
        }
        return Flux.create(sink -> {
            Deque<String> tried = new ArrayDeque<>();
            tryNext(sink, tenantId, messages, tools, options, tried);
        });
    }

    /**
     * Resolves the tenantId in this order:
     * <ol>
     *   <li>{@link TenantContextHolder} — set by {@code TenantContextFilter} on
     *       the current request thread. Works for synchronous calls from the
     *       controller.</li>
     *   <li>{@code metadata.tenantId} on the most recent message that carries
     *       one — propagated by {@code ChatAgentService} so the routing
     *       decorator works correctly when called from a ReAct agent running
     *       on a virtual thread that does not inherit the request's
     *       {@code ThreadLocal}.</li>
     * </ol>
     */
    private String resolveTenantId(List<Msg> messages) {
        String tid = TenantContextHolder.currentTenantId();
        if (tid != null && !tid.isBlank()) return tid;
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m == null) continue;
            Object v = m.getMetadata() == null ? null : m.getMetadata().get("tenantId");
            if (v != null) {
                String s = v.toString();
                if (!s.isBlank()) return s;
            }
        }
        return null;
    }

    private void tryNext(FluxSink<ChatResponse> sink,
                         String tenantId,
                         List<Msg> messages,
                         List<ToolSchema> tools,
                         GenerateOptions options,
                         Deque<String> tried) {

        // 1. Find remaining candidates (those not yet tried)
        List<String> remaining = new ArrayList<>();
        for (String qn : registry.qualifiedNames()) {
            if (!tried.contains(qn)) remaining.add(qn);
        }
        if (remaining.isEmpty()) {
            sink.error(new BizException(ErrorCode.NO_HEALTHY_CANDIDATE,
                    "All candidate models failed for tenant " + tenantId));
            return;
        }

        // 2. Pick the best available
        ChatModelBase chosen;
        try {
            chosen = health.select(tenantId, remaining);
        } catch (BizException ex) {
            sink.error(ex);
            return;
        }
        tried.add(chosen.qualifiedName());

        // 3. Bookkeeping for cancellation on switch
        AtomicReference<Disposable> upstream = new AtomicReference<>();
        AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
        AtomicBoolean switched = new AtomicBoolean(false);
        AtomicLong firstTokenAt = new AtomicLong(0L);
        long callStart = System.currentTimeMillis();

        // 4. Schedule TTFT timer
        long ttftMs = props.getTtftTimeoutMs();
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (firstTokenSeen.get()) return;
            if (!switched.compareAndSet(false, true)) return;
            log.warn("TTFT timeout ({}ms) for model={} tenant={}, switching to next candidate",
                    ttftMs, chosen.qualifiedName(), tenantId);
            health.recordFailure(tenantId, chosen.qualifiedName(),
                    new TimeoutException("TTFT > " + ttftMs + "ms"));
            Disposable d = upstream.getAndSet(null);
            if (d != null) d.dispose();
            // Recurse on a worker thread to avoid stack growth on the scheduler
            scheduler.execute(() -> tryNext(sink, tenantId, messages, tools, options, tried));
        }, ttftMs, TimeUnit.MILLISECONDS);

        // 5. Subscribe to the chosen model
        log.debug("routing.stream start model={} tenant={} tried={}",
                chosen.qualifiedName(), tenantId, tried);
        Disposable sub = chosen.stream(messages, tools, options)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> {
                            if (firstTokenSeen.compareAndSet(false, true)) {
                                firstTokenAt.set(System.currentTimeMillis());
                                timer.cancel(false);
                                long ttft = firstTokenAt.get() - callStart;
                                health.recordSuccess(tenantId, chosen.qualifiedName(), ttft);
                                log.debug("TTFT ok model={} tenant={} ttftMs={}",
                                        chosen.qualifiedName(), tenantId, ttft);
                            }
                            if (switched.get()) return; // already mid-switch
                            sink.next(chunk);
                        },
                        err -> {
                            timer.cancel(false);
                            if (switched.compareAndSet(false, true)) {
                                log.warn("routing.stream error model={} tenant={} cause={}, switching",
                                        chosen.qualifiedName(), tenantId,
                                        err.getClass().getSimpleName());
                                health.recordFailure(tenantId, chosen.qualifiedName(), err);
                                scheduler.execute(() ->
                                        tryNext(sink, tenantId, messages, tools, options, tried));
                            }
                        },
                        () -> {
                            timer.cancel(false);
                            if (switched.get()) return;
                            if (!firstTokenSeen.get()) {
                                long elapsed = System.currentTimeMillis() - callStart;
                                log.warn("routing.stream empty completion model={} tenant={} elapsedMs={}",
                                        chosen.qualifiedName(), tenantId, elapsed);
                                health.recordFailure(tenantId, chosen.qualifiedName(),
                                        new IllegalStateException("Empty stream"));
                                scheduler.execute(() ->
                                        tryNext(sink, tenantId, messages, tools, options, tried));
                                return;
                            }
                            log.debug("routing.stream complete model={} tenant={}",
                                    chosen.qualifiedName(), tenantId);
                            sink.complete();
                        });
        upstream.set(sub);

        // 6. Wire sink cancellation to upstream disposal
        sink.onCancel(() -> {
            timer.cancel(false);
            Disposable d = upstream.getAndSet(null);
            if (d != null) d.dispose();
        });
    }

    /** Test/diagnostic helper. */
    public long configuredTtftTimeoutMs() {
        return props.getTtftTimeoutMs();
    }

    /** Test helper: drain in-flight work. */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
