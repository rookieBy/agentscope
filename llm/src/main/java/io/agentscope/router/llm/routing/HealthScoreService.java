package io.agentscope.router.llm.routing;

import io.agentscope.router.common.tenant.TenantContextHolder;
import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;
import io.agentscope.router.llm.config.RoutingProperties;
import io.agentscope.router.llm.core.ChatModelBase;
import io.agentscope.router.llm.core.HealthSnapshot;
import io.agentscope.router.llm.core.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant, per-model health state and selection. Drives the
 * {@code RoutingChatModel} choice in {@code doStream()}.
 *
 * <p>Thread-safe: the outer map is a {@link ConcurrentHashMap}; nested
 * metrics are guarded by the {@link HealthMetrics} monitor.
 */
@Service
public class HealthScoreService {

    private static final Logger log = LoggerFactory.getLogger(HealthScoreService.class);

    private final ModelRegistry registry;
    private final RoutingProperties props;
    private final Map<String, Map<String, HealthMetrics>> metricsByTenant = new ConcurrentHashMap<>();

    public HealthScoreService(ModelRegistry registry, RoutingProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /** Pick the best healthy candidate for a tenant. Falls back deterministically. */
    public ChatModelBase select(String tenantId, List<String> candidateQualifiedNames) {
        long now = System.currentTimeMillis();
        List<ChatModelBase> candidates = resolve(candidateQualifiedNames);
        if (candidates.isEmpty()) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND,
                    "No candidate models found for tenant " + tenantId);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        ChatModelBase best = null;
        double bestScore = -1.0;
        for (ChatModelBase m : candidates) {
            HealthMetrics hm = metrics(tenantId, m.qualifiedName());
            if (!hm.isAvailable(now)) continue;
            if (hm.score() < props.getMinScoreThreshold()) continue;
            if (hm.score() > bestScore) {
                bestScore = hm.score();
                best = m;
            }
        }
        if (best != null) return best;

        // No candidate passed thresholds — pick the highest score regardless.
        // This avoids failing requests when the system has no recent data;
        // the EMA will catch up and the breaker will engage on real failures.
        log.warn("No healthy candidate for tenant={}, falling back to highest-score", tenantId);
        return candidates.stream()
                .max(Comparator.comparingDouble(m -> metrics(tenantId, m.qualifiedName()).score()))
                .orElseThrow(() -> new BizException(ErrorCode.NO_HEALTHY_CANDIDATE,
                        "All candidate models are unhealthy for tenant " + tenantId));
    }

    /** Record a successful call. */
    public void recordSuccess(String tenantId, String qualifiedName, long ttftMs) {
        HealthMetrics hm = metrics(tenantId, qualifiedName);
        hm.recordSuccess(ttftMs);
        log.debug("health.success tenant={} model={} ttftMs={} score={}",
                tenantId, qualifiedName, ttftMs, String.format("%.3f", hm.score()));
    }

    /** Record a failed call. */
    public void recordFailure(String tenantId, String qualifiedName, Throwable cause) {
        HealthMetrics hm = metrics(tenantId, qualifiedName);
        hm.recordFailure();
        log.warn("health.failure tenant={} model={} cause={} consec={} cooldownMs={}",
                tenantId, qualifiedName,
                cause == null ? "null" : cause.getClass().getSimpleName(),
                hm.consecutiveFailures(), hm.cooldownRemainingMs(System.currentTimeMillis()));
    }

    /** Per-tenant health snapshot, suitable for the {@code /routing/status} endpoint. */
    public List<HealthSnapshot> snapshotFor(String tenantId, List<String> candidateQualifiedNames) {
        long now = System.currentTimeMillis();
        List<HealthSnapshot> out = new ArrayList<>();
        for (String qn : candidateQualifiedNames) {
            HealthMetrics hm = metrics(tenantId, qn);
            out.add(new HealthSnapshot(
                    qn, tenantId,
                    hm.score(),
                    hm.ttftEma(),
                    hm.errorRateEma(),
                    hm.consecutiveFailures(),
                    hm.cooldownRemainingMs(now),
                    Instant.ofEpochMilli(hm.lastUpdateAt())));
        }
        return out;
    }

    /** Convenience: snapshot all models known to the registry for the current tenant. */
    public List<HealthSnapshot> snapshotAllForCurrentTenant() {
        String tid = TenantContextHolder.currentTenantId();
        if (tid == null) {
            throw new BizException(ErrorCode.MISSING_TENANT_ID);
        }
        return snapshotFor(tid, registry.qualifiedNames());
    }

    // --- internals ----------------------------------------------------------

    private HealthMetrics metrics(String tenantId, String qualifiedName) {
        return metricsByTenant
                .computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(qualifiedName, qn -> new HealthMetrics(
                        tenantId, qn,
                        props.getHealthWindowSize(),
                        props.getEmaAlpha(),
                        props.getMaxConsecutiveFailures(),
                        props.getCooldownMs()));
    }

    private List<ChatModelBase> resolve(List<String> qualifiedNames) {
        if (qualifiedNames == null || qualifiedNames.isEmpty()) {
            return new ArrayList<>(registry.all());
        }
        List<ChatModelBase> out = new ArrayList<>(qualifiedNames.size());
        for (String qn : qualifiedNames) {
            registry.find(qn).ifPresent(out::add);
        }
        return out;
    }
}
