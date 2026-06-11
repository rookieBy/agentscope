package io.agentscope.router.llm.provider;

import io.agentscope.router.llm.config.MultimodalProperties;
import io.agentscope.router.llm.config.ProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thin WebClient wrapper for the minimax image / video generation HTTP
 * endpoints. This is the "ProviderAdapter" equivalent for multimodal traffic;
 * the same {@code minimax} provider id is reused (API key + base URL), so any
 * image / video model is billed against the same minimax account.
 *
 * <p>API spec lifted from the official minimax MCP server:
 * <ul>
 *   <li>{@code POST /image_generation}  — sync, returns image URLs.</li>
 *   <li>{@code POST /video_generation}  — async, returns a task id.</li>
 *   <li>{@code GET  /query/video_generation?task_id=...} — poll status.</li>
 *   <li>{@code GET  /files/retrieve?file_id=...} — exchange file id for a download URL.</li>
 *   <li>{@code POST /music_generation}  — sync, returns inline audio bytes (music-01).</li>
 * </ul>
 *
 * <p>Authentication uses {@code Authorization: Bearer <api-key>} as per the
 * official OpenAI-compatible gateway. Errors surface as
 * {@link MiniMaxMultimodalException} with the raw HTTP status + body so the
 * caller can decide how to react (the agent tool typically returns the error
 * verbatim; the controller returns it as a 502 / 504).
 */
public class MiniMaxMultimodalClient {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxMultimodalClient.class);

    public static final String PROVIDER_ID = "minimax";

    private final WebClient webClient;
    private final MultimodalProperties props;
    private final String imageModel;
    private final String videoModel;

    public MiniMaxMultimodalClient(ProviderProperties providers,
                                   MultimodalProperties multimodal) {
        this(buildWebClient(providers), multimodal);
    }

    /**
     * Spring-friendly constructor that takes the api-key + base-url as plain
     * strings. Used by {@code AgentScopeRouterAutoConfiguration} to avoid
     * relying on {@link ProviderProperties}' Map binding, which is known to
     * silently drop entries in some Spring Boot versions when the inner keys
     * are hyphenated.
     */
    public MiniMaxMultimodalClient(String apiKey,
                                   String baseUrl,
                                   MultimodalProperties multimodal) {
        this(buildWebClient(apiKey, baseUrl), multimodal);
    }

    /** Visible for tests. */
    public MiniMaxMultimodalClient(WebClient webClient, MultimodalProperties multimodal) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.props = Objects.requireNonNull(multimodal, "multimodal");
        this.imageModel = multimodal.getMinimax().getImageModel();
        this.videoModel = multimodal.getMinimax().getVideoModel();
    }

    public String imageModel() { return imageModel; }
    public String videoModel() { return videoModel; }

    // ---------- text -> image (sync) ---------------------------------------

    /**
     * Generate one or more images and return the provider's URLs.
     *
     * @param prompt       required, non-blank
     * @param aspectRatio  one of {@code 1:1}, {@code 16:9}, {@code 4:3},
     *                     {@code 3:2}, {@code 2:3}, {@code 3:4}, {@code 9:16},
     *                     {@code 21:9}. Falls back to default when {@code null}.
     * @param n            1..9, falls back to default when {@code null} / invalid.
     */
    public Mono<List<String>> generateImage(String prompt, String aspectRatio, Integer n) {
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new IllegalArgumentException("prompt is required"));
        }
        int count = (n == null || n < 1 || n > 9) ? props.getMinimax().getDefaultImageCount() : n;
        String ratio = (aspectRatio == null || aspectRatio.isBlank())
                ? props.getMinimax().getDefaultAspectRatio() : aspectRatio;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", imageModel);
        body.put("prompt", prompt);
        body.put("aspect_ratio", ratio);
        body.put("n", count);
        body.put("prompt_optimizer", props.getMinimax().isDefaultPromptOptimizer());

        return webClient.post()
                .uri("/v1/image_generation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::extractImageUrls)
                .doOnNext(urls -> log.info("minimax.image.generated count={}", urls.size()))
                .onErrorMap(this::wrapHttpError);
    }

    // ---------- text -> video (async) --------------------------------------

    /**
     * Submit an async video generation task. Returns the provider's task id
     * (distinct from our internal {@code VideoTask.taskId}).
     */
    public Mono<String> submitVideoGeneration(String prompt,
                                              String model,
                                              Integer duration,
                                              String resolution,
                                              String firstFrameImageUrl) {
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new IllegalArgumentException("prompt is required"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", (model == null || model.isBlank()) ? videoModel : model);
        body.put("prompt", prompt);
        if (firstFrameImageUrl != null && !firstFrameImageUrl.isBlank()) {
            body.put("first_frame_image", firstFrameImageUrl);
        }
        if (duration != null) body.put("duration", duration);
        if (resolution != null && !resolution.isBlank()) body.put("resolution", resolution);

        return webClient.post()
                .uri("/v1/video_generation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> Optional.ofNullable((String) resp.get("task_id"))
                        .orElseThrow(() -> new MiniMaxMultimodalException(
                                "minimax /video_generation returned no task_id; body=" + resp)))
                .doOnNext(tid -> log.info("minimax.video.submitted providerTaskId={}", tid))
                .onErrorMap(this::wrapHttpError);
    }

    /**
     * Poll a provider task. {@link VideoPollResult} carries the raw status
     * string so the worker can map it to our internal state machine.
     */
    public Mono<VideoPollResult> queryVideoGeneration(String providerTaskId) {
        if (providerTaskId == null || providerTaskId.isBlank()) {
            return Mono.error(new IllegalArgumentException("providerTaskId is required"));
        }
        return webClient.get()
                .uri(uri -> uri.path("/v1/query/video_generation")
                        .queryParam("task_id", providerTaskId).build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::toPollResult)
                .doOnNext(r -> log.debug("minimax.video.poll providerTaskId={} status={} fileId={}",
                        providerTaskId, r.status(), r.fileId()))
                .onErrorMap(this::wrapHttpError);
    }

    /** Exchange a file id for a downloadable URL. */
    public Mono<String> retrieveFileDownloadUrl(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Mono.error(new IllegalArgumentException("fileId is required"));
        }
        return webClient.get()
                .uri(uri -> uri.path("/v1/files/retrieve")
                        .queryParam("file_id", fileId).build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    Object file = resp.get("file");
                    if (file instanceof Map<?, ?> f) {
                        Object url = f.get("download_url");
                        if (url instanceof String s && !s.isBlank()) return s;
                    }
                    throw new MiniMaxMultimodalException(
                            "minimax /files/retrieve returned no download_url; body=" + resp);
                })
                .onErrorMap(this::wrapHttpError);
    }

    // ---------- text -> music (synchronous) -------------------------------

    /**
     * Generate a piece of music from lyrics + a style description prompt.
     * Returns the audio bytes (or a CDN URL to fetch) synchronously — music-2.6
     * and music-2.6-free have no async task-id pattern like video. The caller
     * is responsible for persisting the bytes / downloading the URL.
     *
     * <p><b>music-2.6 / music-2.6-free schema</b> (per
     * {@code https://platform.minimaxi.com/docs/api-reference/music-generation}):
     * <pre>{@code
     *   { "model": "music-2.6-free",
     *     "prompt": "upbeat pop, suitable for a short promo, ~10 seconds",
     *     "lyrics": "[verse] ... [chorus] ..." }
     * }</pre>
     * Unlike the legacy music-01, music-2.6+ does NOT accept {@code refer_voice}
     * or {@code audio_setting} — passing them yields {@code status_code=2013
     * "cannot use music-01 params on music-1.5/2.6 model"}. The {@code prompt}
     * (style / genre / mood description) is required.
     *
     * <p><b>Successful response shape</b>:
     * <pre>{@code
     *   { "data": { "model": "music-2.6-free",
     *               "audio": "<hex string of mp3 bytes>",
     *               "audio_url": "<signed cdn url>" (optional fallback),
     *               "extra_info": { "music_duration": 25364,
     *                               "music_sample_rate": 44100,
     *                               "music_channel": 2,
     *                               "bitrate": 256000,
     *                               "music_size": 4115529 },
     *               "file_extension": "mp3" },
     *     "base_resp": { "status_code": 0, "status_msg": "success" } }
     * }</pre>
     * A {@code data.audio_url} (signed URL) is also accepted as a fallback
     * if the API ever switches to URL-only responses.
     */
    public Mono<MusicResult> generateMusic(String model,
                                           String prompt,
                                           String lyrics) {
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "prompt is required for music-2.6+ endpoints "
                    + "(style / genre / mood description)"));
        }
        if (lyrics == null || lyrics.isBlank()) {
            return Mono.error(new IllegalArgumentException("lyrics is required"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", (model == null || model.isBlank()) ? "music-2.6-free" : model);
        body.put("prompt", prompt);
        body.put("lyrics", lyrics);

        return webClient.post()
                .uri("/v1/music_generation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::toMusicResult)
                .doOnNext(r -> log.info("minimax.music.generated model={} audioLenMs={} bytes={} ext={}",
                        r.model(), r.audioLengthMs(), r.audioBytes() == null ? -1 : r.audioBytes().length,
                        r.fileExtension()))
                .onErrorMap(this::wrapHttpError);
    }

    // ---------- helpers ----------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<String> extractImageUrls(Map<?, ?> resp) {
        Object data = resp.get("data");
        if (data instanceof Map<?, ?> dm) {
            Object urls = dm.get("image_urls");
            if (urls instanceof List<?> list) {
                return list.stream()
                        .filter(o -> o instanceof String)
                        .map(o -> (String) o)
                        .toList();
            }
        }
        throw new MiniMaxMultimodalException(
                "minimax /v1/image_generation returned unexpected body: " + resp);
    }

    @SuppressWarnings("unchecked")
    private VideoPollResult toPollResult(Map<?, ?> resp) {
        String status = Optional.ofNullable((String) resp.get("status")).orElse("Unknown");
        String fileId = (String) resp.get("file_id");
        Object baseResp = resp.get("base_resp");
        if (baseResp instanceof Map<?, ?> br) {
            Object code = br.get("status_code");
            Object msg = br.get("status_msg");
            if (code instanceof Number n && n.intValue() != 0) {
                throw new MiniMaxMultimodalException(
                        "minimax video poll API error " + code + " - " + msg);
            }
        }
        return new VideoPollResult(status, fileId);
    }

    /**
     * Parse the synchronous music-generation response. The official docs at
     * {@code https://platform.minimaxi.com/docs/api-reference/music-generation}
     * describe the music-2.6+ shape (with {@code data.extra_info} carrying
     * duration / sample-rate / bitrate / size). We also still read the
     * legacy top-level {@code audio_length} / {@code file_size} /
     * {@code file_extension} fields as a fallback:
     * <pre>{@code
     *   { "data": { "model": "music-2.6-free",
     *               "audio": "<hex-encoded mp3 bytes>",
     *               "extra_info": { "music_duration": 25364,
     *                               "music_sample_rate": 44100,
     *                               "music_channel": 2,
     *                               "bitrate": 256000,
     *                               "music_size": 4115529 } },
     *     "base_resp": { "status_code": 0, "status_msg": "success" } }
     * }</pre>
     * A {@code data.audio_url} (signed URL) is also accepted as a fallback
     * if the provider ever switches to URL-only delivery.
     */
    @SuppressWarnings("unchecked")
    private MusicResult toMusicResult(Map<?, ?> resp) {
        Object data = resp.get("data");
        if (!(data instanceof Map<?, ?> dm)) {
            throw new MiniMaxMultimodalException(
                    "minimax /v1/music_generation returned unexpected body: " + resp);
        }
        Object baseResp = resp.get("base_resp");
        if (baseResp instanceof Map<?, ?> br) {
            Object code = br.get("status_code");
            Object msg = br.get("status_msg");
            if (code instanceof Number n && n.intValue() != 0) {
                throw new MiniMaxMultimodalException(
                        "minimax music API error " + code + " - " + msg);
            }
        }
        String model = (String) dm.get("model");
        byte[] audioBytes = null;
        String audioUrl = null;
        Object audioObj = dm.get("audio");
        if (audioObj instanceof String hex && !hex.isBlank()) {
            audioBytes = HexFormat.of().parseHex(hex);
        } else {
            Object urlObj = dm.get("audio_url");
            if (urlObj instanceof String s && !s.isBlank()) {
                audioUrl = s;
            }
        }
        if (audioBytes == null && audioUrl == null) {
            throw new MiniMaxMultimodalException(
                    "minimax /v1/music_generation returned no audio or audio_url; body=" + resp);
        }
        // music-2.6+ packs audio metadata under data.extra_info; fall back
        // to legacy top-level fields for older music-1.5 responses.
        long audioLengthMs = 0L;
        long fileSize = 0L;
        long sampleRate = 0L;
        long bitrate = 0L;
        long channel = 0L;
        Object extra = dm.get("extra_info");
        if (extra instanceof Map<?, ?> ei) {
            audioLengthMs = toLong(ei.get("music_duration"));
            fileSize = toLong(ei.get("music_size"));
            sampleRate = toLong(ei.get("music_sample_rate"));
            bitrate = toLong(ei.get("bitrate"));
            channel = toLong(ei.get("music_channel"));
        }
        if (audioLengthMs == 0L) audioLengthMs = toLong(dm.get("audio_length"));
        if (fileSize == 0L) fileSize = toLong(dm.get("file_size"));
        if (audioBytes != null) fileSize = fileSize == 0L ? audioBytes.length : fileSize;
        String fileExtension = Optional.ofNullable((String) dm.get("file_extension"))
                .filter(s -> !s.isBlank()).orElse("mp3");
        return new MusicResult(model, audioBytes, audioUrl, audioLengthMs, fileSize,
                sampleRate, bitrate, channel, fileExtension);
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return 0L;
    }

    private Throwable wrapHttpError(Throwable err) {
        if (err instanceof WebClientResponseException wre) {
            return new MiniMaxMultimodalException(
                    "minimax HTTP " + wre.getStatusCode().value() + ": " + wre.getResponseBodyAsString(),
                    wre);
        }
        if (err instanceof MiniMaxMultimodalException) return err;
        return new MiniMaxMultimodalException("minimax call failed: " + err.getMessage(), err);
    }

    private static WebClient buildWebClient(ProviderProperties providers) {
        ProviderProperties.ProviderConfig cfg = providers.getEntries().get(PROVIDER_ID);
        if (cfg == null) {
            throw new IllegalStateException(
                    "minimax provider not configured — set agentscope.providers.minimax.api-key/base-url");
        }
        return buildWebClient(cfg.getApiKey(), cfg.getBaseUrl());
    }

    private static WebClient buildWebClient(String apiKey, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "minimax api-key is missing — set MINIMAX_API_KEY env var or agentscope.providers.minimax.api-key");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "minimax base-url is missing — set agentscope.providers.minimax.base-url");
        }
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader("MM-API-Source", "agentscope-router")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    // ---------- value types ------------------------------------------------

    /**
     * Raw status returned by {@code /v1/query/video_generation}. Mapped to the
     * internal {@code VideoTaskState} by the worker.
     */
    public record VideoPollResult(String status, String fileId) {
        public static final String STATUS_QUEUEING = "Queueing";
        public static final String STATUS_PROCESSING = "Processing";
        public static final String STATUS_SUCCESS = "Success";
        public static final String STATUS_FAIL = "Fail";
    }

    /**
     * Audio-generation parameters for the legacy music-01 / music-02 API.
     * The {@code format} field must be one of {@code mp3}, {@code pcm},
     * {@code flac} (per the spec); bitrate is ignored for lossless formats.
     *
     * @deprecated music-2.6+ does not accept {@code audio_setting}. The
     * record is kept so the public API does not break for callers that
     * still hold a reference; {@link #generateMusic} ignores it.
     */
    @Deprecated
    public record AudioSetting(int sampleRate, int bitrate, String format) {
        /** Sensible default for music-01: 44.1 kHz, 256 kbps CBR, mp3. */
        public static AudioSetting mp3() {
            return new AudioSetting(44100, 256000, "mp3");
        }
    }

    /**
     * Successful response from {@code /v1/music_generation}. Exactly one of
     * {@code audioBytes} and {@code audioUrl} is non-null: music-2.6-free
     * currently returns inline hex bytes; if the provider ever switches to
     * signed-URL delivery only, the {@code audioUrl} field is populated and
     * the caller is responsible for fetching the bytes.
     *
     * <p>Newer fields {@code sampleRateHz} / {@code bitrate} / {@code channel}
     * are populated from {@code data.extra_info} for music-2.6+; they will
     * be {@code 0} for older music-1.5 responses that don't include the
     * block.
     */
    public record MusicResult(String model,
                              byte[] audioBytes,
                              String audioUrl,
                              long audioLengthMs,
                              long fileSize,
                              long sampleRateHz,
                              long bitrate,
                              long channel,
                              String fileExtension) {
        public boolean hasInlineBytes() { return audioBytes != null; }
    }

    /** Wraps HTTP / parse errors from the minimax multimodal API. */
    public static class MiniMaxMultimodalException extends RuntimeException {
        public MiniMaxMultimodalException(String message) { super(message); }
        public MiniMaxMultimodalException(String message, Throwable cause) { super(message, cause); }
    }
}
