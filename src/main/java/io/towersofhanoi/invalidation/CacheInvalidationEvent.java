package io.towersofhanoi.invalidation;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

/**
 * Payload published to the Redis invalidation channel when a cache entry is evicted or cleared.
 * A null {@code key} signals a full-cache clear.
 */
public class CacheInvalidationEvent implements Serializable {

    private String cacheName;
    private String key;

    public CacheInvalidationEvent() {}

    public CacheInvalidationEvent(String cacheName, String key) {
        this.cacheName = cacheName;
        this.key = key;
    }

    public String getCacheName() { return cacheName; }
    public void setCacheName(String cacheName) { this.cacheName = cacheName; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    @JsonIgnore
    public boolean isFullClear() { return key == null; }
}
