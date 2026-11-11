package io.towersofhanoi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for the multi-level cache.
 *
 * <pre>
 * multilevel.cache:
 *   enabled: true
 *   local:
 *     maximum-size: 10000
 *     ttl: 5m
 *   distributed:
 *     ttl: 1h
 *     cache-null-values: false
 *   invalidation:
 *     enabled: true
 *     channel: multilevel:cache:invalidation
 *   caches:
 *     products:
 *       local-ttl: 2m
 *       distributed-ttl: 30m
 * </pre>
 */
@ConfigurationProperties(prefix = "multilevel.cache")
public class MultiLevelCacheProperties {

    private boolean enabled = true;

    @NestedConfigurationProperty
    private LocalCacheConfig local = new LocalCacheConfig();

    @NestedConfigurationProperty
    private DistributedCacheConfig distributed = new DistributedCacheConfig();

    @NestedConfigurationProperty
    private InvalidationConfig invalidation = new InvalidationConfig();

    /** Per-cache overrides keyed by cache name. */
    private Map<String, CacheConfig> caches = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public LocalCacheConfig getLocal() { return local; }
    public void setLocal(LocalCacheConfig local) { this.local = local; }

    public DistributedCacheConfig getDistributed() { return distributed; }
    public void setDistributed(DistributedCacheConfig distributed) { this.distributed = distributed; }

    public InvalidationConfig getInvalidation() { return invalidation; }
    public void setInvalidation(InvalidationConfig invalidation) { this.invalidation = invalidation; }

    public Map<String, CacheConfig> getCaches() { return caches; }
    public void setCaches(Map<String, CacheConfig> caches) { this.caches = caches; }

    public static class LocalCacheConfig {

        /** Maximum number of entries in the local Caffeine cache. */
        private long maximumSize = 10_000;

        /** Time-to-live for local cache entries. Shorter than distributed to prefer freshness. */
        private Duration ttl = Duration.ofSeconds(300);

        public long getMaximumSize() { return maximumSize; }
        public void setMaximumSize(long maximumSize) { this.maximumSize = maximumSize; }

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }

    public static class DistributedCacheConfig {

        /** Time-to-live for Redis cache entries. */
        private Duration ttl = Duration.ofSeconds(3600);

        /**
         * Whether to cache null return values.
         * When false (default), null results are not stored and will trigger a reload on every access.
         */
        private boolean cacheNullValues = false;

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }

        public boolean isCacheNullValues() { return cacheNullValues; }
        public void setCacheNullValues(boolean cacheNullValues) { this.cacheNullValues = cacheNullValues; }
    }

    public static class InvalidationConfig {

        /** Whether to enable cross-instance local cache invalidation via Redis pub/sub. */
        private boolean enabled = true;

        /** Redis pub/sub channel used for broadcasting invalidation events. */
        private String channel = "multilevel:cache:invalidation";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
    }
}
