package io.towersofhanoi.invalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.towersofhanoi.core.MultiLevelCache;
import io.towersofhanoi.core.MultiLevelCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.Nullable;

/**
 * Subscribes to the Redis invalidation channel and evicts stale entries from
 * the local Caffeine cache without touching Redis (which would create an event loop).
 */
public class CacheInvalidationListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationListener.class);

    private final MultiLevelCacheManager cacheManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CacheInvalidationListener(MultiLevelCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onMessage(Message message, @Nullable byte[] pattern) {
        try {
            CacheInvalidationEvent event = objectMapper.readValue(message.getBody(), CacheInvalidationEvent.class);
            Cache cache = cacheManager.getCache(event.getCacheName());
            if (!(cache instanceof MultiLevelCache mlc)) {
                return;
            }
            if (event.isFullClear()) {
                mlc.clearLocalOnly();
                log.debug("[{}] Local cache cleared via invalidation event", event.getCacheName());
            } else {
                mlc.evictLocalOnly(event.getKey());
                log.debug("[{}] Local cache evicted key '{}' via invalidation event",
                    event.getCacheName(), event.getKey());
            }
        } catch (Exception e) {
            log.warn("Failed to process cache invalidation event: {}", e.getMessage());
        }
    }
}
