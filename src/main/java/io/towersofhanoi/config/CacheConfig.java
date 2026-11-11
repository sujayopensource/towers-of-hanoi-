package io.towersofhanoi.config;

import java.time.Duration;

/**
 * Per-cache configuration overrides. Any null field falls back to the global default.
 */
public class CacheConfig {

    private Long localMaximumSize;
    private Duration localTtl;
    private Duration distributedTtl;
    private Boolean cacheNullValues;

    public Long getLocalMaximumSize() { return localMaximumSize; }
    public void setLocalMaximumSize(Long localMaximumSize) { this.localMaximumSize = localMaximumSize; }

    public Duration getLocalTtl() { return localTtl; }
    public void setLocalTtl(Duration localTtl) { this.localTtl = localTtl; }

    public Duration getDistributedTtl() { return distributedTtl; }
    public void setDistributedTtl(Duration distributedTtl) { this.distributedTtl = distributedTtl; }

    public Boolean getCacheNullValues() { return cacheNullValues; }
    public void setCacheNullValues(Boolean cacheNullValues) { this.cacheNullValues = cacheNullValues; }
}
