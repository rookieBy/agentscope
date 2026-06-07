package io.agentscope.router.llm.config;

import io.agentscope.router.llm.core.ModelRegistry;
import io.agentscope.router.llm.provider.MiniMaxMultimodalClient;
import io.agentscope.router.llm.routing.HealthScoreService;
import io.agentscope.router.llm.core.RoutingChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Defines the routing-layer beans. Provider-to-adapter registration is
 * handled by {@link ProviderRegistration} once the context is refreshed
 * (after all beans are constructed — that ordering is what avoids the
 * configuration-vs-bean cycle).
 */
@Configuration
@EnableConfigurationProperties({ProviderProperties.class, RoutingProperties.class,
        TenantProperties.class, MultimodalProperties.class})
public class AgentScopeRouterAutoConfiguration {

    @Bean
    public ModelRegistry modelRegistry() {
        return new ModelRegistry();
    }

    @Bean
    public HealthScoreService healthScoreService(ModelRegistry registry, RoutingProperties props) {
        return new HealthScoreService(registry, props);
    }

    @Bean
    public RoutingChatModel routingChatModel(ModelRegistry registry,
                                             HealthScoreService health,
                                             RoutingProperties props) {
        return new RoutingChatModel(registry, health, props);
    }

    /**
     * HTTP client for the minimax image / video endpoints. Reuses the
     * provider's api-key + base-url — there is one minimax account per
     * deployment, billed for chat, image and video traffic alike.
     *
     * <p>The api-key and base-url are read from the {@link Environment}
     * (keys {@code agentscope.providers.minimax.api-key} /
     * {@code agentscope.providers.minimax.base-url}) so we don't depend
     * on {@link ProviderProperties}' map binding, which is known to
     * return empty entries in some Spring Boot versions when the
     * inner keys are hyphenated.
     */
    @Bean
    public MiniMaxMultimodalClient miniMaxMultimodalClient(Environment env,
                                                           MultimodalProperties multimodal) {
        return new MiniMaxMultimodalClient(
                env.getProperty("agentscope.providers.minimax.api-key", ""),
                env.getProperty("agentscope.providers.minimax.base-url", "https://api.minimaxi.com/v1"),
                multimodal);
    }
}
