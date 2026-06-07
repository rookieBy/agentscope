package io.agentscope.router.api.dto;

/**
 * Request body for {@code POST /api/v1/demo/ai-promo}.
 *
 * <ul>
 *   <li>{@code topic} — required, non-blank. The subject of the promo.</li>
 *   <li>{@code duration} — optional. When set, must be {@code 6} or {@code 10}
 *       (Hailuo-2.3 API constraint). When null, the server-side default is
 *       used (see {@code agentscope.demo.ai-promo.default-duration}).</li>
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
