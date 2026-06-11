package io.agentscope.router.business.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AI-promo demo. Bound from
 * {@code agentscope.demo.ai-promo.*} in {@code application.yml}.
 *
 * <p>The whole {@code ai-promo} sub-tree is gated by
 * {@link #isEnabled()}; when false, the {@code AiPromoDemoService} bean is
 * not registered and the {@code /api/v1/demo/ai-promo} endpoint returns 404.
 */
@ConfigurationProperties(prefix = "agentscope.demo.ai-promo")
public class DemoProperties {

    private boolean enabled = false;
    private int defaultDuration = 10;
    private String defaultResolution = "768P";
    private String defaultModel = "MiniMax-Hailuo-2.3";
    private String outputDir = "./agentscope-output";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDefaultDuration() { return defaultDuration; }
    public void setDefaultDuration(int defaultDuration) { this.defaultDuration = defaultDuration; }

    public String getDefaultResolution() { return defaultResolution; }
    public void setDefaultResolution(String defaultResolution) {
        this.defaultResolution = defaultResolution;
    }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
}
