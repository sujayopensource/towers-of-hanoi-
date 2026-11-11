package io.towersofhanoi.core;

import io.towersofhanoi.invalidation.CacheInvalidationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Two-tier cache: Caffeine (L1, local) in front of Redis (L2, distributed).
 *
 * <p>Lookup order: L1 → L2 → miss. On an L2 hit the value is back-filled into L1.
 * Writes go to both layers (write-through). Evictions propagate to both layers and
 * optionally broadcast an invalidation event so peer instances can clear their L1.
 */
public class MultiLevelCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelCache.class);

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> localCache;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Duration distributedTtl;
    private final boolean cacheNullValues;
    @Nullable private final CacheInvalidationPublisher invalidationPublisher;

    public MultiLevelCache(String name,
                           com.github.benmanes.caffeine.cache.Cache<Object, Object> localCache,
                           RedisTemplate<String, Object> redisTemplate,
                           Duration distributedTtl,
                           boolean cacheNullValues,
                           @Nullable CacheInvalidationPublisher invalidationPublisher) {
        this.name = name;
        this.localCache = localCache;
        this.redisTemplate = redisTemplate;
        this.distributedTtl = distributedTtl;
        this.cacheNullValues = cacheNullValues;
        this.invalidationPublisher = invalidationPublisher;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        // L1
        Object local = localCache.getIfPresent(key);
        if (local != null) {
            return toValueWrapper(local);
        }

        // L2
        try {
            Object remote = redisTemplate.opsForValue().get(redisKey(key));
            if (remote != null) {
                localCache.put(key, remote);
                return toValueWrapper(remote);
            }
        } catch (Exception e) {
            log.warn("[{}] Redis get failed for key '{}': {}", name, key, e.getMessage());
        }

        return null;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) return null;
        Object value = wrapper.get();
        if (type != null && value != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                "Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        T value;
        try {
            value = valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
        put(key, value);
        return value;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        if (value == null && !cacheNullValues) {
            log.debug("[{}] Skipping null value for key '{}'", name, key);
            return;
        }
        Object store = toStoreValue(value);
        localCache.put(key, store);
        try {
            redisTemplate.opsForValue().set(redisKey(key), store, distributedTtl);
        } catch (Exception e) {
            log.warn("[{}] Redis put failed for key '{}': {}", name, key, e.getMessage());
        }
    }

    @Override
    public void evict(Object key) {
        localCache.invalidate(key);
        try {
            redisTemplate.delete(redisKey(key));
        } catch (Exception e) {
            log.warn("[{}] Redis delete failed for key '{}': {}", name, key, e.getMessage());
        }
        if (invalidationPublisher != null) {
            invalidationPublisher.publish(name, key.toString());
        }
    }

    @Override
    public void clear() {
        localCache.invalidateAll();
        try {
            Set<String> keys = redisTemplate.keys(name + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("[{}] Redis clear failed: {}", name, e.getMessage());
        }
        if (invalidationPublisher != null) {
            invalidationPublisher.publishClear(name);
        }
    }

    /** Evicts only from L1. Called by {@link io.towersofhanoi.invalidation.CacheInvalidationListener} to avoid re-publishing. */
    public void evictLocalOnly(Object key) {
        localCache.invalidate(key);
    }

    /** Clears only L1. Called by {@link io.towersofhanoi.invalidation.CacheInvalidationListener} to avoid re-publishing. */
    public void clearLocalOnly() {
        localCache.invalidateAll();
    }

    public com.github.benmanes.caffeine.cache.Cache<Object, Object> getLocalCache() {
        return localCache;
    }

    private String redisKey(Object key) {
        return name + ":" + key;
    }

    private Object toStoreValue(@Nullable Object value) {
        return (value == null) ? NullValueMarker.INSTANCE : value;
    }

    @Nullable
    private Object fromStoreValue(Object storeValue) {
        return (storeValue instanceof NullValueMarker) ? null : storeValue;
    }

    private ValueWrapper toValueWrapper(Object storeValue) {
        return () -> fromStoreValue(storeValue);
    }
}
