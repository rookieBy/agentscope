package io.agentscope.router.business.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Provides the singleton {@link RedisMessageListenerContainer} that
 * {@link io.agentscope.router.business.multimodal.VideoTaskEventListener}
 * uses to subscribe to per-task video-event channels. The container's
 * {@code start()} / {@code stop()} lifecycle is delegated to the listener
 * (annotated with {@code @PostConstruct} / {@code @PreDestroy}).
 *
 * <p>We deliberately do <strong>not</strong> declare
 * {@code spring.data.redis.listener.*} properties — the container is built
 * from the auto-configured {@link RedisConnectionFactory} so it inherits the
 * same Lettuce pool and sentinel/cluster config as the rest of the app.
 */
@Configuration
public class RedisListenerConfig {

    @Bean(destroyMethod = "stop")
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(connectionFactory);
        return c;
    }
}
