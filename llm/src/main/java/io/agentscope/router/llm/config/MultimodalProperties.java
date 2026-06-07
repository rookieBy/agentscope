package io.agentscope.router.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code agentscope.multimodal.*} from {@code application.yml}.
 *
 * <p>YAML structure:
 * <pre>
 * agentscope:
 *   multimodal:
 *     enabled: true
 *     minimax:
 *       image-model: image-01
 *       video-model: MiniMax-Hailuo-2.3
 *       # defaults: aspectRatio=1:1, n=1, prompt-optimizer=true
 * </pre>
 *
 * <p>API key and base URL are reused from {@link ProviderProperties}
 * (provider id {@code "minimax"}), keeping the two surfaces in sync.
 */
@ConfigurationProperties(prefix = "agentscope.multimodal")
public class MultimodalProperties {

    private boolean enabled = true;
    private Minimax minimax = new Minimax();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Minimax getMinimax() { return minimax; }
    public void setMinimax(Minimax minimax) { this.minimax = minimax == null ? new Minimax() : minimax; }

    public static class Minimax {
        private String imageModel = "image-01";
        private String videoModel = "MiniMax-Hailuo-2.3";
        private String defaultAspectRatio = "1:1";
        private int defaultImageCount = 1;
        private boolean defaultPromptOptimizer = true;

        public String getImageModel() { return imageModel; }
        public void setImageModel(String imageModel) {
            this.imageModel = (imageModel == null || imageModel.isBlank()) ? "image-01" : imageModel;
        }

        public String getVideoModel() { return videoModel; }
        public void setVideoModel(String videoModel) {
            this.videoModel = (videoModel == null || videoModel.isBlank()) ? "MiniMax-Hailuo-2.3" : videoModel;
        }

        public String getDefaultAspectRatio() { return defaultAspectRatio; }
        public void setDefaultAspectRatio(String s) {
            this.defaultAspectRatio = (s == null || s.isBlank()) ? "1:1" : s;
        }

        public int getDefaultImageCount() { return defaultImageCount <= 0 ? 1 : defaultImageCount; }
        public void setDefaultImageCount(int n) { this.defaultImageCount = n; }

        public boolean isDefaultPromptOptimizer() { return defaultPromptOptimizer; }
        public void setDefaultPromptOptimizer(boolean b) { this.defaultPromptOptimizer = b; }
    }
}
