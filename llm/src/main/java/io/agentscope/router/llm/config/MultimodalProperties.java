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
 *       music-model: music-2.6-free
 *       music-prompt: upbeat pop, suitable for a short promo, ~10 seconds
 *       # refer-voice / audio-setting are kept for backward compatibility
 *       # with old yml but are no longer sent to the provider — music-2.6+
 *       # uses `prompt` (style description) instead.
 *       refer-voice: ""
 *       audio-setting:
 *         sample-rate: 44100
 *         bitrate: 256000
 *         format: mp3
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
        private String musicModel = "music-2.6-free";
        /**
         * Style / genre description sent as the {@code prompt} field of
         * minimax's music-2.6+ endpoint. Required for music-2.6 / 2.6-free
         * — the API rejects requests that omit it.
         */
        private String musicPrompt = "upbeat pop, suitable for a short promo, ~10 seconds";
        /**
         * @deprecated Kept for yml backward compatibility only. music-2.6+
         * no longer uses TTS {@code refer_voice} — the style is carried in
         * {@link #musicPrompt} instead. This field is never sent to the
         * provider and may be removed in a future release.
         */
        @Deprecated
        private String referVoice;
        /**
         * @deprecated Kept for yml backward compatibility only. music-2.6+
         * does not accept {@code audio_setting} — bitrate / sample-rate are
         * decided by the provider.
         */
        @Deprecated
        private AudioSetting audioSetting = new AudioSetting();
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

        public String getMusicModel() { return musicModel; }
        public void setMusicModel(String musicModel) {
            this.musicModel = (musicModel == null || musicModel.isBlank()) ? "music-2.6-free" : musicModel;
        }

        public String getMusicPrompt() { return musicPrompt; }
        public void setMusicPrompt(String musicPrompt) {
            this.musicPrompt = (musicPrompt == null || musicPrompt.isBlank())
                    ? "upbeat pop, suitable for a short promo, ~10 seconds"
                    : musicPrompt.trim();
        }

        /** @deprecated See {@link #referVoice}. */
        @Deprecated
        public String getReferVoice() { return referVoice; }
        /** @deprecated See {@link #referVoice}. */
        @Deprecated
        public void setReferVoice(String referVoice) { this.referVoice = referVoice; }

        /** @deprecated See {@link #audioSetting}. */
        @Deprecated
        public AudioSetting getAudioSetting() { return audioSetting; }
        /** @deprecated See {@link #audioSetting}. */
        @Deprecated
        public void setAudioSetting(AudioSetting audioSetting) {
            this.audioSetting = audioSetting == null ? new AudioSetting() : audioSetting;
        }

        public String getDefaultAspectRatio() { return defaultAspectRatio; }
        public void setDefaultAspectRatio(String s) {
            this.defaultAspectRatio = (s == null || s.isBlank()) ? "1:1" : s;
        }

        public int getDefaultImageCount() { return defaultImageCount <= 0 ? 1 : defaultImageCount; }
        public void setDefaultImageCount(int n) { this.defaultImageCount = n; }

        public boolean isDefaultPromptOptimizer() { return defaultPromptOptimizer; }
        public void setDefaultPromptOptimizer(boolean b) { this.defaultPromptOptimizer = b; }

        /**
         * Maps to the {@code audio_setting} block of the minimax
         * {@code /v1/music_generation} request body.
         */
        public static class AudioSetting {
            private int sampleRate = 44100;
            private int bitrate = 256000;
            private String format = "mp3";

            public int getSampleRate() { return sampleRate <= 0 ? 44100 : sampleRate; }
            public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

            public int getBitrate() { return bitrate <= 0 ? 256000 : bitrate; }
            public void setBitrate(int bitrate) { this.bitrate = bitrate; }

            public String getFormat() { return format; }
            public void setFormat(String format) {
                this.format = (format == null || format.isBlank()) ? "mp3" : format;
            }
        }
    }
}
