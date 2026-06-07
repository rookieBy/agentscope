package io.agentscope.router.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code agentscope.tenants.*}. Each entry is a tenant-scoped override
 * for provider list / strategy / rate limit. The special key {@code "default"}
 * supplies the global fallback used when a tenant has no override.
 */
@ConfigurationProperties(prefix = "agentscope.tenants")
public class TenantProperties {

    private Map<String, TenantConfig> entries = new LinkedHashMap<>();

    public Map<String, TenantConfig> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, TenantConfig> entries) {
        this.entries = entries == null ? new LinkedHashMap<>() : entries;
    }

    public static class TenantConfig {
        /** Empty list = follow the global registry. */
        private List<String> providers = List.of();
        /** Routing strategy: "best-score" (default), "round-robin", "cost-first". */
        private String strategy = "best-score";
        /** Per-tenant rate limit (requests per minute). 0 means unlimited. */
        private int rateLimitRpm = 0;

        public List<String> getProviders() { return providers == null ? List.of() : providers; }
        public void setProviders(List<String> providers) { this.providers = providers == null ? List.of() : providers; }

        public String getStrategy() { return strategy == null ? "best-score" : strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }

        public int getRateLimitRpm() { return rateLimitRpm; }
        public void setRateLimitRpm(int rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }
    }
}
