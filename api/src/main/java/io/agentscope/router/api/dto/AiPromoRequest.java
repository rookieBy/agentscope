package io.agentscope.router.api.dto;

/**
 * Request body for {@code POST /api/v1/demo/ai-music}.
 *
 * <ul>
 *   <li>{@code topic} — required, non-blank. The subject of the music promo.</li>
 *   <li>{@code duration} — optional. When set, must be in {@code [1, 600]} seconds
 *       (soft UX guard, not a music-01 constraint). When null, the server-side
 *       default is used (see {@code agentscope.demo.ai-promo.default-duration}).</li>
 *   <li>{@code language} — optional. Defaults to {@code "en"} when blank.</li>
 * </ul>
 */
public record AiPromoRequest(
        String topic,
        Integer duration,
        String language) {

    public AiPromoRequest {
        if (topic == null) topic = "";
        if (language == null) language = "";
    }
}
