package io.towersofhanoi.core;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.towersofhanoi.config.CacheConfig;
import io.towersofhanoi.config.MultiLevelCacheProperties;
import io.towersofhanoi.invalidation.CacheInvalidationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@link CacheManager} that creates and manages {@link MultiLevelCache} instances.
 *
 * <p>Cache instances are created lazily on first access and held for the application lifetime.
 * Per-cache configuration overrides are resolved at creation time by merging with global defaults.
 */
public class MultiLevelCacheManager implements CacheManager, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheManager.class);

    private final ConcurrentHashMap<String, Cache> cacheMap = new ConcurrentHashMap<>(16);
    private final MultiLevelCacheProperties properties;
    private final RedisTemplate<String, Object> redisTemplate;
    @Nullable private final CacheInvalidationPublisher invalidationPublisher;
    @Nullable private final MeterRegistry meterRegistry;

    public MultiLevelCacheManager(MultiLevelCacheProperties properties,
                                   RedisTemplate<String, Object> redisTemplate,
                                   @Nullable CacheInvalidationPublisher invalidationPublisher,
                                   @Nullable MeterRegistry meterRegistry) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.invalidationPublisher = invalidationPublisher;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Cache getCache(String name) {
        return cacheMap.computeIfAbsent(name, this::createCache);
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(cacheMap.keySet());
    }

    private Cache createCache(String name) {
        CacheConfig overrides = properties.getCaches().get(name);

        long maxSize = resolve(overrides, CacheConfig::getLocalMaximumSize, properties.getLocal().getMaximumSize());
        Duration localTtl = resolve(overrides, CacheConfig::getLocalTtl, properties.getLocal().getTtl());
        Duration distributedTtl = resolve(overrides, CacheConfig::getDistributedTtl, properties.getDistributed().getTtl());
        boolean cacheNullValues = resolve(overrides, CacheConfig::getCacheNullValues, properties.getDistributed().isCacheNullValues());

        Caffeine<Object, Object> builder = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(localTtl)
            .recordStats();

        com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeine = builder.build();

        bindMetrics(name, caffeine);

        log.debug("Created MultiLevelCache '{}': localMax={}, localTtl={}, redisTtl={}",
            name, maxSize, localTtl, distributedTtl);

        return new MultiLevelCache(name, caffeine, redisTemplate, distributedTtl, cacheNullValues, invalidationPublisher);
    }

    private void bindMetrics(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeine) {
        if (meterRegistry == null) return;
        CaffeineCacheMetrics.monitor(meterRegistry, caffeine, name, Collections.emptyList());
    }

    @Override
    public void destroy() {
        cacheMap.values().stream()
            .filter(c -> c instanceof MultiLevelCache)
            .map(c -> (MultiLevelCache) c)
            .forEach(c -> c.getLocalCache().invalidateAll());
        cacheMap.clear();
    }

    private <V> V resolve(@Nullable CacheConfig overrides, Function<CacheConfig, V> getter, V defaultValue) {
        if (overrides == null) return defaultValue;
        V v = getter.apply(overrides);
        return v != null ? v : defaultValue;
    }
}
