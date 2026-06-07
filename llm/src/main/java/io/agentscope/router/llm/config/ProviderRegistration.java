package io.agentscope.router.llm.config;

import io.agentscope.router.llm.core.ModelRegistry;
import io.agentscope.router.llm.provider.DashScopeProviderAdapter;
import io.agentscope.router.llm.provider.MiniMaxProviderAdapter;
import io.agentscope.router.llm.provider.OpenAIProviderAdapter;
import io.agentscope.router.llm.provider.ProviderAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * After the context is fully wired, walks {@link ProviderProperties} and
 * registers one {@link ProviderAdapter} per enabled (provider, model) pair
 * into the shared {@link ModelRegistry}.
 *
 * <p>Lives outside the {@code @Configuration} class so that field injection
 * of {@code ModelRegistry} doesn't form a cycle with the {@code @Bean} that
 * produces it.
 *
 * <p>Disabled providers, providers with no API key, and providers with an
 * empty {@code models} list are silently skipped. The application is
 * allowed to start with an empty registry (useful for local development
 * and the M1 health-smoke test).
 */
@Component
public class ProviderRegistration {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistration.class);

    @Autowired
    private ProviderProperties providerProperties;
    @Autowired
    private ModelRegistry registry;
    @Autowired
    private Environment env;

    @EventListener(ContextRefreshedEvent.class)
    public void registerModels() {
        log.info("ProviderProperties entries: {}", providerProperties.getEntries());
        var entries = providerProperties.getEntries();
        // Fallback: if @ConfigurationProperties Map binding returned empty (older
        // Spring Boot quirks, mismatched property metadata), walk the Environment
        // and read each known provider's keys directly.
        if (entries.isEmpty()) {
            entries = readFromEnvironment();
            log.info("Falling back to Environment-based provider config: {}", entries.keySet());
        }
        int registered = 0;
        for (var entry : entries.entrySet()) {
            String providerId = entry.getKey();
            var cfg = entry.getValue();
            if (cfg == null || !cfg.isEnabled()) {
                log.debug("Provider '{}' is disabled — skipping", providerId);
                continue;
            }
            if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
                log.info("Provider '{}' is enabled but has no api-key — skipping", providerId);
                continue;
            }
            if (cfg.getModels().isEmpty()) {
                log.info("Provider '{}' is enabled but has no models configured — skipping", providerId);
                continue;
            }
            for (String modelName : cfg.getModels()) {
                ProviderAdapter adapter = buildAdapter(providerId, modelName, cfg);
                if (adapter == null) continue;
                registry.register(adapter);
                registered++;
                log.info("Registered model: {} (provider={})", adapter.qualifiedName(), providerId);
            }
        }
        log.info("Model registry populated with {} entr{}", registered, registered == 1 ? "y" : "ies");
        if (registry.isEmpty()) {
            log.warn("No models registered. Set agentscope.providers.*.enabled=true and provide API keys to enable LLM calls.");
        }
    }

    /** Read provider config directly from the Spring {@link Environment}. */
    private java.util.Map<String, ProviderProperties.ProviderConfig> readFromEnvironment() {
        java.util.Map<String, ProviderProperties.ProviderConfig> out = new java.util.LinkedHashMap<>();
        for (String id : new String[]{"openai", "dashscope", "minimax"}) {
            String prefix = "agentscope.providers." + id;
            boolean enabled = env.getProperty(prefix + ".enabled", Boolean.class, false);
            if (!enabled) continue;
            String apiKey = env.getProperty(prefix + ".api-key", "");
            String baseUrl = env.getProperty(prefix + ".base-url", "");
            String protocol = env.getProperty(prefix + ".protocol", "");
            // For YAML list values, try a few property-name shapes:
            //   1. .models             (comma-joined string)
            //   2. .models[0]          (indexed — what Spring's PropertyResolver exposes)
            String[] modelArr = env.getProperty(prefix + ".models", String[].class, new String[0]);
            java.util.List<String> models;
            if (modelArr.length > 0) {
                models = java.util.Arrays.asList(modelArr);
            } else {
                java.util.List<String> idx = new java.util.ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    String v = env.getProperty(prefix + ".models[" + i + "]");
                    if (v == null) break;
                    idx.add(v);
                }
                models = idx;
            }
            log.info("ProviderRegistration.readFromEnvironment id={} enabled={} apiKey.len={} baseUrl={} models={}",
                    id, enabled, apiKey == null ? 0 : apiKey.length(), baseUrl, models);
            var cfg = new ProviderProperties.ProviderConfig();
            cfg.setEnabled(true);
            cfg.setApiKey(apiKey);
            cfg.setBaseUrl(baseUrl);
            cfg.setProtocol(protocol);
            cfg.setModels(models);
            out.put(id, cfg);
        }
        return out;
    }

    private ProviderAdapter buildAdapter(String providerId, String modelName, ProviderProperties.ProviderConfig cfg) {
        try {
            return switch (providerId.toLowerCase()) {
                case DashScopeProviderAdapter.PROVIDER_ID ->
                        new DashScopeProviderAdapter(modelName, cfg.getApiKey());
                case OpenAIProviderAdapter.PROVIDER_ID ->
                        new OpenAIProviderAdapter(modelName, cfg.getApiKey(), cfg.getBaseUrl());
                case MiniMaxProviderAdapter.PROVIDER_ID ->
                        new MiniMaxProviderAdapter(modelName, cfg.getApiKey(), cfg.getBaseUrl());
                default -> {
                    log.warn("Unknown provider id '{}' for model '{}' — skipping", providerId, modelName);
                    yield null;
                }
            };
        } catch (RuntimeException ex) {
            log.error("Failed to build adapter for provider='{}' model='{}': {}",
                    providerId, modelName, ex.getMessage());
            return null;
        }
    }
}
