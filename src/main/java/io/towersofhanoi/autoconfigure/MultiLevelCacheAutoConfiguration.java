package io.towersofhanoi.autoconfigure;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.towersofhanoi.config.MultiLevelCacheProperties;
import io.towersofhanoi.core.MultiLevelCacheManager;
import io.towersofhanoi.invalidation.CacheInvalidationListener;
import io.towersofhanoi.invalidation.CacheInvalidationPublisher;
import io.towersofhanoi.serialization.GenericJacksonRedisSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.lang.Nullable;

/**
 * Spring Boot auto-configuration for the multi-level cache library.
 *
 * <p>Activated automatically when both Caffeine and Spring Data Redis are on the classpath.
 * Add {@code @EnableCaching} to your application class and the multi-level cache manager
 * becomes the active {@link CacheManager} with no further setup required.
 *
 * <p>All beans are {@code @ConditionalOnMissingBean} so consumers can override any component
 * (serializer, Redis template, cache manager) while keeping the rest of the defaults.
 */
@AutoConfiguration(before = CacheAutoConfiguration.class, after = RedisAutoConfiguration.class)
@ConditionalOnClass({Caffeine.class, RedisConnectionFactory.class, CacheManager.class})
@ConditionalOnProperty(prefix = "multilevel.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MultiLevelCacheProperties.class)
public class MultiLevelCacheAutoConfiguration {

    @Bean(name = "multiLevelCacheSerializer")
    @ConditionalOnMissingBean(name = "multiLevelCacheSerializer")
    public GenericJacksonRedisSerializer multiLevelCacheSerializer() {
        return new GenericJacksonRedisSerializer();
    }

    @Bean(name = "multiLevelCacheRedisTemplate")
    @ConditionalOnMissingBean(name = "multiLevelCacheRedisTemplate")
    public RedisTemplate<String, Object> multiLevelCacheRedisTemplate(
            RedisConnectionFactory connectionFactory,
            @Qualifier("multiLevelCacheSerializer") GenericJacksonRedisSerializer serializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnMissingBean(CacheInvalidationPublisher.class)
    @ConditionalOnProperty(prefix = "multilevel.cache.invalidation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    public CacheInvalidationPublisher cacheInvalidationPublisher(
            StringRedisTemplate stringRedisTemplate,
            MultiLevelCacheProperties properties) {
        return new CacheInvalidationPublisher(stringRedisTemplate,
            properties.getInvalidation().getChannel());
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public MultiLevelCacheManager multiLevelCacheManager(
            MultiLevelCacheProperties properties,
            @Qualifier("multiLevelCacheRedisTemplate") RedisTemplate<String, Object> redisTemplate,
            @Autowired(required = false) CacheInvalidationPublisher invalidationPublisher,
            @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
        return new MultiLevelCacheManager(properties, redisTemplate, invalidationPublisher, meterRegistry);
    }

    @Bean(name = "multiLevelCacheInvalidationContainer")
    @ConditionalOnMissingBean(name = "multiLevelCacheInvalidationContainer")
    @ConditionalOnProperty(prefix = "multilevel.cache.invalidation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer multiLevelCacheInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            MultiLevelCacheManager cacheManager,
            MultiLevelCacheProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
            new CacheInvalidationListener(cacheManager),
            new ChannelTopic(properties.getInvalidation().getChannel()));
        return container;
    }
}
