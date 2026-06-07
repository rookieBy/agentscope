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
                .uri("/image_generation")
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
                .uri("/video_generation")
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
                .uri(uri -> uri.path("/query/video_generation")
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
                .uri(uri -> uri.path("/files/retrieve")
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

    /** Wraps HTTP / parse errors from the minimax multimodal API. */
    public static class MiniMaxMultimodalException extends RuntimeException {
        public MiniMaxMultimodalException(String message) { super(message); }
        public MiniMaxMultimodalException(String message, Throwable cause) { super(message, cause); }
    }
}
