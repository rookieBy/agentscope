package io.agentscope.router.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Request body for {@code POST /api/v1/chat/stream}. Accepts the simple
 * OpenAI-shaped {@code messages} array.
 */
public record ChatRequest(List<Map<String, String>> messages) {

    public ChatRequest {
        if (messages == null) messages = List.of();
    }
}
