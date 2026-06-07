package io.agentscope.router.business.multimodal;

import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;

/**
 * Downloads a remote file (e.g. the video file produced by minimax) into a
 * local {@link Path}. Uses a plain {@link WebClient} without auth headers
 * because the download URLs returned by
 * {@code /v1/files/retrieve} are publicly signed URLs on minimax's CDN.
 *
 * <p>Used by the {@code download_video_file} {@code @Tool} method on
 * {@code PromoDemoTools} to fulfil the AI-promo demo's last step.
 */
@Component
public class VideoFileDownloader {

    private static final Logger log = LoggerFactory.getLogger(VideoFileDownloader.class);

    private final WebClient webClient;

    public VideoFileDownloader() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, "agentscope-router/video-downloader")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                .build();
    }

    /**
     * GET the file at {@code url} and write it to {@code destination}.
     * Creates parent directories if missing. Returns the absolute destination
     * path on success.
     *
     * @throws BizException with {@link ErrorCode#VIDEO_DOWNLOAD_FAILED} on
     *                      any HTTP / IO failure.
     */
    public Mono<Path> download(String url, Path destination) {
        if (url == null || url.isBlank()) {
            return Mono.error(new BizException(ErrorCode.VIDEO_DOWNLOAD_FAILED,
                    "download url is required"));
        }
        if (destination == null) {
            return Mono.error(new BizException(ErrorCode.VIDEO_DOWNLOAD_FAILED,
                    "destination path is required"));
        }
        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
        } catch (IOException e) {
            return Mono.error(new BizException(ErrorCode.VIDEO_DOWNLOAD_FAILED,
                    "Cannot create parent dir for " + destination,
                    Map.of("cause", e.getMessage())));
        }
        log.info("video.download.start url={} -> {}", url, destination);
        return webClient.get()
                .uri(URI.create(url))
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofMinutes(5))
                .flatMap(bytes -> writeBytes(bytes, destination))
                .doOnSuccess(p -> log.info("video.download.ok path={} bytes={}",
                        p, p.toFile().length()))
                .onErrorMap(this::wrapError);
    }

    private Mono<Path> writeBytes(byte[] bytes, Path destination) {
        try {
            Files.write(destination, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return Mono.just(destination.toAbsolutePath());
        } catch (IOException e) {
            return Mono.error(new BizException(ErrorCode.VIDEO_DOWNLOAD_FAILED,
                    "Failed to write " + destination,
                    Map.of("cause", e.getMessage())));
        }
    }

    private Throwable wrapError(Throwable err) {
        if (err instanceof BizException b) return b;
        if (err instanceof WebClientResponseException wre) {
            return new BizException(ErrorCode.VIDEO_DOWNLOAD_FAILED,
                    "HTTP " + wre.getStatusCode().value() + " downloading video: "
                            + wre.getResponseBodyAsString(),
                    Map.of("status", wre.getStatusCode().value()));
        }
        return new BizException(ErrorCode.VIDEO_DOWNLOAD_FAILED,
                "Video download failed: " + err.getMessage(),
                Map.of("type", err.getClass().getSimpleName()));
    }
}
