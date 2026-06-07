package io.agentscope.router.llm.provider;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;

/**
 * Adapter that wraps AgentScope's {@link DashScopeChatModel}. The model is
 * built lazily on first call to {@link #buildDelegate()} (or eagerly when the
 * constructor is invoked with a non-null delegate).
 */
public class DashScopeProviderAdapter extends ProviderAdapter {

    public static final String PROVIDER_ID = "dashscope";

    private final String apiKey;

    public DashScopeProviderAdapter(String modelName, String apiKey, Model delegate) {
        super(modelName, delegate);
        this.apiKey = apiKey;
    }

    public DashScopeProviderAdapter(String modelName, String apiKey) {
        this(modelName, apiKey, null);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    protected Model buildDelegate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "DashScope api-key is not configured for model '" + getModelName() + "'");
        }
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(getModelName())
                .stream(true)
                .build();
    }
}
