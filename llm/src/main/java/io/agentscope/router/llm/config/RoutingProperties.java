package io.agentscope.router.llm.config;

import io.agentscope.router.common.constants.Constants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentscope.routing.*}. Values are consumed by M3's
 * {@code RoutingChatModel} and {@code HealthScoreService}.
 */
@ConfigurationProperties(prefix = "agentscope.routing")
public class RoutingProperties {

    private boolean enabled = true;
    private int ttftTimeoutMs = Constants.DEFAULT_TTFT_TIMEOUT_MS;
    private int healthWindowSize = Constants.DEFAULT_HEALTH_WINDOW_SIZE;
    private double emaAlpha = Constants.DEFAULT_EMA_ALPHA;
    private double minScoreThreshold = Constants.DEFAULT_MIN_SCORE_THRESHOLD;
    private int maxConsecutiveFailures = Constants.DEFAULT_MAX_CONSECUTIVE_FAILURES;
    private long cooldownMs = Constants.DEFAULT_COOLDOWN_MS;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTtftTimeoutMs() { return ttftTimeoutMs; }
    public void setTtftTimeoutMs(int ttftTimeoutMs) { this.ttftTimeoutMs = ttftTimeoutMs; }

    public int getHealthWindowSize() { return healthWindowSize; }
    public void setHealthWindowSize(int healthWindowSize) { this.healthWindowSize = healthWindowSize; }

    public double getEmaAlpha() { return emaAlpha; }
    public void setEmaAlpha(double emaAlpha) { this.emaAlpha = emaAlpha; }

    public double getMinScoreThreshold() { return minScoreThreshold; }
    public void setMinScoreThreshold(double minScoreThreshold) { this.minScoreThreshold = minScoreThreshold; }

    public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
    public void setMaxConsecutiveFailures(int maxConsecutiveFailures) { this.maxConsecutiveFailures = maxConsecutiveFailures; }

    public long getCooldownMs() { return cooldownMs; }
    public void setCooldownMs(long cooldownMs) { this.cooldownMs = cooldownMs; }
}
