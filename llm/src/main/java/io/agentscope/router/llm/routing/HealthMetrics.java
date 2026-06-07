package io.agentscope.router.llm.routing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-(tenant, model) rolling health metrics. Backed by a sliding window and
 * exponential moving averages (EMA) for fast, drift-free scoring.
 *
 * <p>All fields are mutable, all mutating methods are {@code synchronized} so
 * that concurrent calls from the routing layer do not corrupt the EMA. Reads
 * ({@link #score()}, {@link #isAvailable()}) are lock-free best-effort — they
 * may return slightly stale values, which is acceptable for a routing decision.
 */
public class HealthMetrics {

    private final String tenantId;
    private final String qualifiedName;
    private final int windowSize;
    private final double emaAlpha;
    private final int maxConsecutiveFailures;
    private final long cooldownMs;

    private final Deque<Long> ttftWindow = new ArrayDeque<>();
    private final Deque<Boolean> outcomeWindow = new ArrayDeque<>();
    private final AtomicLong lastUpdateAt = new AtomicLong(0L);

    // EMA state — guarded by `this` for writes; reads best-effort volatile.
    private volatile double ttftEma = 0.0;
    private volatile double errorRateEma = 0.0;
    private volatile int consecutiveFailures = 0;
    private volatile long cooldownUntil = 0L;

    public HealthMetrics(String tenantId, String qualifiedName,
                         int windowSize, double emaAlpha,
                         int maxConsecutiveFailures, long cooldownMs) {
        this.tenantId = tenantId;
        this.qualifiedName = qualifiedName;
        this.windowSize = Math.max(1, windowSize);
        this.emaAlpha = clamp01(emaAlpha);
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.cooldownMs = Math.max(0L, cooldownMs);
    }

    /** Record a successful call. {@code ttftMs} is the time-to-first-token. */
    public synchronized void recordSuccess(long ttftMs) {
        long safeTtft = Math.max(0L, ttftMs);
        push(ttftWindow, safeTtft);
        push(outcomeWindow, Boolean.TRUE);
        ttftEma = ema(ttftEma, safeTtft);
        errorRateEma = ema(errorRateEma, 0.0);
        consecutiveFailures = 0;
        cooldownUntil = 0L;
        lastUpdateAt.set(System.currentTimeMillis());
    }

    /** Record a failed call. */
    public synchronized void recordFailure() {
        push(outcomeWindow, Boolean.FALSE);
        errorRateEma = ema(errorRateEma, 1.0);
        consecutiveFailures++;
        if (consecutiveFailures >= maxConsecutiveFailures) {
            cooldownUntil = System.currentTimeMillis() + cooldownMs;
        }
        lastUpdateAt.set(System.currentTimeMillis());
    }

    /**
     * Combined health score in {@code [0, 1]}. Higher is healthier. Formula:
     * <pre>
     *   speed    = 1 / (1 + ttftEma/1000)
     *   success  = 1 - errorRateEma
     *   penalty  = exp(-consecutiveFailures)
     *   recency  = 1.0 if updated in last 5 min, else 0.5
     *   score    = speed * success * penalty * recency
     * </pre>
     */
    public synchronized double score() {
        double speed = 1.0 / (1.0 + ttftEma / 1000.0);
        double success = Math.max(0.0, 1.0 - errorRateEma);
        double penalty = Math.exp(-consecutiveFailures);
        double recency = recencyFactor();
        return speed * success * penalty * recency;
    }

    /** {@code true} if the model can be selected right now. */
    public synchronized boolean isAvailable(long nowMillis) {
        if (cooldownUntil > nowMillis) return false;
        if (consecutiveFailures >= maxConsecutiveFailures) return false;
        return true;
    }

    public synchronized long cooldownRemainingMs(long nowMillis) {
        return Math.max(0L, cooldownUntil - nowMillis);
    }

    public String tenantId() { return tenantId; }
    public String qualifiedName() { return qualifiedName; }
    public synchronized double ttftEma() { return ttftEma; }
    public synchronized double errorRateEma() { return errorRateEma; }
    public synchronized int consecutiveFailures() { return consecutiveFailures; }
    public long lastUpdateAt() { return lastUpdateAt.get(); }

    // --- internals ----------------------------------------------------------

    private double recencyFactor() {
        long last = lastUpdateAt.get();
        if (last == 0L) return 0.5; // never observed
        long age = System.currentTimeMillis() - last;
        return age < 300_000L ? 1.0 : 0.5;
    }

    private <T> void push(Deque<T> dq, T value) {
        if (dq.size() >= windowSize) dq.pollFirst();
        dq.addLast(value);
    }

    private double ema(double prev, double sample) {
        if (prev == 0.0) return sample; // seed
        return emaAlpha * sample + (1.0 - emaAlpha) * prev;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
