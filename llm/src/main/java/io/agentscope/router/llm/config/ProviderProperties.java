package io.agentscope.router.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code agentscope.providers.<name>.*} entries from {@code application.yml}.
 *
 * <p>YAML structure:
 * <pre>
 * agentscope:
 *   providers:
 *     openai:
 *       enabled: true
 *       api-key: sk-...
 *       base-url: https://api.openai.com/v1
 *       models: [gpt-4o-mini, gpt-4o]
 *     dashscope:
 *       enabled: false
 *       api-key: ${DASHSCOPE_API_KEY:}
 *       models: [qwen-plus]
 *     minimax:
 *       enabled: true
 *       api-key: ${MINIMAX_API_KEY:}
 *       base-url: ${MINIMAX_BASE_URL:https://api.minimax.chat/v1}
 *       protocol: openai-compatible
 *       models: []
 * </pre>
 */
@ConfigurationProperties(prefix = "agentscope.providers")
public class ProviderProperties {

    /** Map keyed by provider id (openai / dashscope / minimax / ...). */
    private Map<String, ProviderConfig> entries = new LinkedHashMap<>();

    public Map<String, ProviderConfig> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, ProviderConfig> entries) {
        this.entries = entries == null ? new LinkedHashMap<>() : entries;
    }

    public static class ProviderConfig {
        /** Whether to instantiate a chat model for this provider. */
        private boolean enabled = false;
        /** API key (typically {@code ${ENV_VAR:}} placeholder). */
        private String apiKey = "";
        /** Optional custom base URL (e.g. for OpenAI-compatible gateways). */
        private String baseUrl = "";
        /** Wire protocol — currently only "openai-compatible" is recognised. */
        private String protocol = "";
        /** List of model names to register. Empty list means "no models". */
        private List<String> models = List.of();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl == null ? "" : baseUrl; }

        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol == null ? "" : protocol; }

        public List<String> getModels() { return models == null ? List.of() : models; }
        public void setModels(List<String> models) { this.models = models == null ? List.of() : models; }
    }
}
