package io.agentscope.router.api.dto;

/**
 * Request body for the direct multimodal endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/media/image} — synchronous text-to-image.</li>
 *   <li>{@code POST /api/v1/media/video} — asynchronous text-to-video.</li>
 * </ul>
 *
 * <p>All fields other than {@code prompt} are optional hints forwarded to the
 * underlying provider; the provider may still apply its own defaults.
 */
public record MediaRequest(
        String prompt,
        String aspectRatio,
        Integer n,
        Integer duration,
        String resolution,
        String firstFrameImageUrl
) {

    public MediaRequest {
        if (prompt == null) prompt = "";
        if (aspectRatio == null) aspectRatio = "";
        if (resolution == null) resolution = "";
        if (firstFrameImageUrl == null) firstFrameImageUrl = "";
    }
}
