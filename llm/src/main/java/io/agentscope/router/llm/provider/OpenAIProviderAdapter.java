package io.agentscope.router.llm.provider;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;

/**
 * Adapter wrapping AgentScope's {@link OpenAIChatModel}. Honors a custom
 * {@code baseUrl} so it can be used for OpenAI-compatible gateways.
 */
public class OpenAIProviderAdapter extends ProviderAdapter {

    public static final String PROVIDER_ID = "openai";

    private final String apiKey;
    private final String baseUrl;

    public OpenAIProviderAdapter(String modelName, String apiKey, String baseUrl, Model delegate) {
        super(modelName, delegate);
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public OpenAIProviderAdapter(String modelName, String apiKey, String baseUrl) {
        this(modelName, apiKey, baseUrl, null);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    protected Model buildDelegate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI api-key is not configured for model '" + getModelName() + "'");
        }
        var builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(getModelName())
                .stream(true);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }
}
