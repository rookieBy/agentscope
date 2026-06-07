package io.agentscope.router.llm.provider;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;

/**
 * Adapter for the {@code MiniMax} OpenAI-compatible gateway. Reuses
 * {@link OpenAIChatModel} with a custom {@code baseUrl}; only the provider id
 * differs so routing / registry treat it as its own provider family.
 */
public class MiniMaxProviderAdapter extends ProviderAdapter {

    public static final String PROVIDER_ID = "minimax";

    private final String apiKey;
    private final String baseUrl;

    public MiniMaxProviderAdapter(String modelName, String apiKey, String baseUrl, Model delegate) {
        super(modelName, delegate);
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public MiniMaxProviderAdapter(String modelName, String apiKey, String baseUrl) {
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
                    "MiniMax api-key is not configured for model '" + getModelName() + "'");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "MiniMax base-url is not configured for model '" + getModelName() + "'");
        }
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(getModelName())
                .stream(true)
                .build();
    }
}
