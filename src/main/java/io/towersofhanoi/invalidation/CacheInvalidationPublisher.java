package io.towersofhanoi.invalidation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;

/**
 * Publishes local-cache invalidation events to a Redis pub/sub channel so that
 * all JVM instances can evict their local copies of a stale entry.
 */
public class CacheInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final String channel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CacheInvalidationPublisher(StringRedisTemplate redisTemplate, String channel) {
        this.redisTemplate = redisTemplate;
        this.channel = channel;
    }

    public void publish(String cacheName, @Nullable String key) {
        try {
            String payload = objectMapper.writeValueAsString(new CacheInvalidationEvent(cacheName, key));
            redisTemplate.convertAndSend(channel, payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to publish cache invalidation event for cache '{}', key '{}': {}",
                cacheName, key, e.getMessage());
        }
    }

    public void publishClear(String cacheName) {
        publish(cacheName, null);
    }
}
