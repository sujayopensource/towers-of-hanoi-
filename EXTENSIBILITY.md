# Towers of Hanoi — Extensibility Roadmap: Pluggable Cache Providers

This document describes the phased plan to make the cache library fully provider-agnostic.
Today L1 is hardwired to Caffeine and L2 to Redis. The goal is to allow any combination —
Guava, HashMap, Memcached, or a custom implementation — via configuration alone.

---

## Contents

- [Current state](#current-state)
- [Target architecture](#target-architecture)
- [Phase 1 — Provider SPI (pure refactor)](#phase-1--provider-spi-pure-refactor)
- [Phase 2 — Additional L1 providers](#phase-2--additional-l1-providers)
- [Phase 3 — Memcached L2 provider](#phase-3--memcached-l2-provider)
- [Phase 4 — Single-tier mode](#phase-4--single-tier-mode)
- [Phase 5 — Custom provider beans](#phase-5--custom-provider-beans)
- [Configuration schema evolution](#configuration-schema-evolution)
- [Backward compatibility contract](#backward-compatibility-contract)
- [Open questions](#open-questions)

---

## Current state

```
MultiLevelCacheManager
    └── MultiLevelCache
            ├── L1: com.github.benmanes.caffeine.cache.Cache  ← hardcoded
            └── L2: RedisTemplate<String, Object>             ← hardcoded
```

Both types are referenced directly inside `MultiLevelCache`. Swapping either tier requires
forking the class.

---

## Target architecture

```
MultiLevelCacheManager
    └── MultiLevelCache
            ├── L1: LocalCache          ← interface
            │       ├── CaffeineLocalCache
            │       ├── GuavaLocalCache
            │       ├── HashMapLocalCache
            │       └── <custom>
            └── L2: DistributedCache    ← interface
                    ├── RedisDistributedCache
                    ├── MemcachedDistributedCache
                    └── <custom>

InvalidationBroker                      ← decoupled from L2
    ├── RedisInvalidationBroker  (pub/sub)
    ├── NoOpInvalidationBroker   (single-instance or L2 has no pub/sub)
    └── <future: Kafka, RabbitMQ>
```

The key principle: `MultiLevelCache` talks only to `LocalCache` and `DistributedCache`.
It has no knowledge of Caffeine, Redis, or any concrete library.

---

## Phase 1 — Provider SPI (pure refactor)

**Goal:** Introduce the provider interfaces without changing any observable behaviour.
This phase is a prerequisite for all subsequent phases.

### New interfaces

```java
/**
 * Creates LocalCache instances for a given cache name and config.
 * Implement this to plug in a custom L1 provider.
 */
public interface L1CacheProvider {
    LocalCache create(String cacheName, ResolvedCacheConfig config);
    String providerName();
}

/**
 * Abstraction over any in-process, bounded, TTL-aware store.
 */
public interface LocalCache {
    Object getIfPresent(Object key);
    void put(Object key, Object value);
    void invalidate(Object key);
    void invalidateAll();
    Optional<LocalCacheStats> stats();   // feeds Micrometer; empty = no metrics
}

/**
 * Creates DistributedCache instances for a given cache name and config.
 * Implement this to plug in a custom L2 provider.
 */
public interface L2CacheProvider {
    DistributedCache create(String cacheName, ResolvedCacheConfig config);
    String providerName();
    boolean supportsInvalidationBroadcast();  // false for Memcached, true for Redis
}

/**
 * Abstraction over any shared, TTL-aware remote store.
 */
public interface DistributedCache {
    Object get(Object key);
    void put(Object key, Object value, Duration ttl);
    void delete(Object key);
    void deleteByPattern(String namePattern);  // used by cache.clear()
}

/**
 * Broadcasts and receives cross-instance invalidation events.
 * Decoupled from L2: a Memcached L2 can still use a Redis invalidation broker.
 */
public interface InvalidationBroker {
    void publish(String cacheName, String key);       // key=null means full clear
    void subscribe(InvalidationHandler handler);
    boolean isAvailable();
}
```

### New concrete implementations

| Class | Wraps |
|-------|-------|
| `CaffeineL1CacheProvider` | Existing Caffeine construction logic from `MultiLevelCacheManager` |
| `CaffeineLocalCache` | `com.github.benmanes.caffeine.cache.Cache` |
| `RedisL2CacheProvider` | Existing `RedisTemplate` logic from `MultiLevelCache` |
| `RedisDistributedCache` | `RedisTemplate<String, Object>` |
| `RedisInvalidationBroker` | Existing pub/sub publisher + listener |

### Changes to existing classes

| Class | Change |
|-------|--------|
| `MultiLevelCache` | Replace `com.github.benmanes.caffeine.cache.Cache` field with `LocalCache`; replace `RedisTemplate` field with `DistributedCache` |
| `MultiLevelCacheManager` | Accept `L1CacheProvider` and `L2CacheProvider`; delegate `createCache()` to them |
| `MultiLevelCacheAutoConfiguration` | Register `CaffeineL1CacheProvider` and `RedisL2CacheProvider` as default beans |

### Configuration

No new properties in this phase. The existing `multilevel.cache.local.*` and
`multilevel.cache.distributed.*` namespaces remain unchanged.

### Deliverable

Zero behaviour change. All 19 existing tests must stay green.
The only observable difference: provider name appears in startup log.

---

## Phase 2 — Additional L1 providers

**Goal:** Allow Guava and ConcurrentHashMap as L1 alternatives, selectable by config.

### New providers

#### GuavaL1CacheProvider

```java
com.google.common.cache.CacheBuilder.newBuilder()
    .maximumSize(config.localMaximumSize())
    .expireAfterWrite(config.localTtl())
    .recordStats()
    .build();
```

Requires: `com.google.guava:guava` on classpath.
Metrics: Guava `CacheStats` mapped to `LocalCacheStats`.

#### ConcurrentHashMapL1CacheProvider

Backed by `ConcurrentHashMap`. No built-in TTL — a scheduled `ScheduledExecutorService`
sweeps expired entries every `local.ttl / 2` using an insertion-timestamp sidecar map.

Suitable for: testing, environments where no Caffeine/Guava is desired.
Not suitable for: bounded memory — no eviction by size, only by TTL.

### Auto-selection order (classpath-based)

When `l1.provider` is not explicitly set:

```
Caffeine on classpath? → CaffeineL1CacheProvider  (default)
Guava on classpath?    → GuavaL1CacheProvider
fallback               → ConcurrentHashMapL1CacheProvider
```

### Configuration

```yaml
multilevel:
  cache:
    l1:
      provider: caffeine    # caffeine | guava | hashmap | none
      caffeine:
        maximum-size: 10000
        ttl: 5m
      guava:
        maximum-size: 10000
        ttl: 5m
      hashmap:
        ttl: 5m             # swept on a background thread; no size bound
```

### Dependency requirements per provider

| Provider | Required dependency |
|----------|-------------------|
| `caffeine` | `com.github.ben-manes.caffeine:caffeine` (already present) |
| `guava` | `com.google.guava:guava` |
| `hashmap` | None (pure JDK) |

---

## Phase 3 — Memcached L2 provider

**Goal:** Allow Memcached as an L2 alternative to Redis.

### New provider: MemcachedL2CacheProvider

Backed by [XMemcached](https://github.com/killme2008/xmemcached) (preferred over Spymemcached,
which is unmaintained).

```java
MemcachedClientBuilder builder = new XMemcachedClientBuilder(
    AddrUtil.getAddresses(config.memcached().servers()));
builder.setConnectionPoolSize(config.memcached().maxConnections());
MemcachedClient client = builder.build();
```

Serialisation: same `GenericJacksonRedisSerializer` logic, adapted to byte arrays.

#### Key constraint: no pub/sub

Memcached has no native pub/sub mechanism. `MemcachedL2CacheProvider.supportsInvalidationBroadcast()` returns `false`.

The `InvalidationBroker` is now **decoupled from L2**. When L2 is Memcached, the
invalidation broker defaults to `NoOpInvalidationBroker` (single-instance safe) unless
the user separately configures a Redis broker.

```yaml
multilevel:
  cache:
    l2:
      provider: memcached
    invalidation:
      broker: redis        # redis | none  (default: redis if Redis is on classpath)
      channel: multilevel:cache:invalidation
```

This means you can run:
- L2 = Memcached + Invalidation broker = Redis (hybrid: cheap Memcached storage, Redis for coordination)
- L2 = Memcached + Invalidation broker = none (single-instance or accept stale L1)

#### Memcached limitations vs Redis

| Feature | Redis | Memcached |
|---------|-------|-----------|
| Pub/sub invalidation | Yes | No (needs separate broker) |
| `clear()` by prefix | `KEYS pattern` / `SCAN` | No — Memcached has no key enumeration |
| TTL precision | Milliseconds | Seconds only |
| Value size limit | 512 MB | 1 MB (default) |
| Cluster topology | Redis Cluster / Sentinel | Consistent hashing (client-side) |

`MemcachedDistributedCache.deleteByPattern()` cannot be efficiently implemented.
`clear()` will log a `WARN` and no-op on the L2 layer when backed by Memcached;
only L1 is cleared. Document this limitation prominently.

### Configuration

```yaml
multilevel:
  cache:
    l2:
      provider: memcached      # redis | memcached | none
      memcached:
        servers: "host1:11211 host2:11211"
        ttl: 1h
        max-connections: 10
        operation-timeout: 1s
      redis:
        ttl: 1h
```

### New dependency

```kotlin
compileOnly("com.googlecode.xmemcached:xmemcached:2.4.8")
```

---

## Phase 4 — Single-tier mode

**Goal:** Support L1-only or L2-only operation for simpler deployments.

| Mode | Config | Use case |
|------|--------|----------|
| L1 only | `l2.provider: none` | Local dev, single-instance, in-memory testing |
| L2 only | `l1.provider: none` | Multiple instances sharing state, no local heap budget |
| Both (default) | — | Production, horizontally scaled |

### New providers

`NoOpL1CacheProvider` → `NullLocalCache` (always misses, put/evict are no-ops)
`NoOpL2CacheProvider` → `NullDistributedCache` (always misses, put/evict are no-ops)

`MultiLevelCache` already handles null results from both tiers;
the null providers make single-tier degenerate cleanly without conditional logic.

### Configuration

```yaml
multilevel:
  cache:
    l1:
      provider: none     # disables L1 entirely
    l2:
      provider: redis    # L2 only → pure shared cache
```

```yaml
multilevel:
  cache:
    l1:
      provider: caffeine
    l2:
      provider: none     # L1 only → pure local cache
    invalidation:
      enabled: false     # no point broadcasting if no L2
```

---

## Phase 5 — Custom provider beans

**Goal:** Full extensibility via Spring bean registration. Users implement the SPI and
declare a bean — no library changes required.

### Registration

All provider beans use `@ConditionalOnMissingBean`, so a user-declared bean wins automatically:

```java
@Bean
public L1CacheProvider myL1CacheProvider() {
    return new RocksDBL1CacheProvider(...);   // off-heap, persistent L1
}

@Bean
public L2CacheProvider myL2CacheProvider() {
    return new HazelcastL2CacheProvider(...); // Hazelcast IMDG as L2
}

@Bean
public InvalidationBroker myInvalidationBroker() {
    return new KafkaInvalidationBroker(...);  // Kafka for invalidation events
}
```

### Documentation requirement

Each interface must carry Javadoc that specifies:
- Threading contract (all methods must be thread-safe)
- Null contract (`getIfPresent` returns null on miss, never throws)
- Error contract (throw `CacheProviderException` on unrecoverable errors; swallowed by `MultiLevelCache`)

---

## Configuration schema evolution

### Phase 1 (no change)

```yaml
multilevel:
  cache:
    enabled: true
    local:
      maximum-size: 10000
      ttl: 5m
    distributed:
      ttl: 1h
      cache-null-values: false
    invalidation:
      enabled: true
      channel: multilevel:cache:invalidation
```

### Phase 2+ (new l1 / l2 namespaces; old namespace kept as aliases)

```yaml
multilevel:
  cache:
    enabled: true

    l1:
      provider: caffeine          # caffeine | guava | hashmap | none
      caffeine:
        maximum-size: 10000
        ttl: 5m
      guava:
        maximum-size: 10000
        ttl: 5m
      hashmap:
        ttl: 5m

    l2:
      provider: redis             # redis | memcached | none
      redis:
        ttl: 1h
        cache-null-values: false
      memcached:
        servers: "localhost:11211"
        ttl: 1h
        max-connections: 10
        operation-timeout: 1s

    invalidation:
      enabled: true
      broker: redis               # redis | none  (Phase 3+)
      channel: multilevel:cache:invalidation

    caches:
      products:
        l1-maximum-size: 50000
        l1-ttl: 2m
        l2-ttl: 30m
```

---

## Backward compatibility contract

| Property (old) | Mapped to (new) | Deprecated in |
|---------------|-----------------|--------------|
| `multilevel.cache.local.maximum-size` | `multilevel.cache.l1.caffeine.maximum-size` | Phase 2 |
| `multilevel.cache.local.ttl` | `multilevel.cache.l1.caffeine.ttl` | Phase 2 |
| `multilevel.cache.distributed.ttl` | `multilevel.cache.l2.redis.ttl` | Phase 2 |
| `multilevel.cache.distributed.cache-null-values` | `multilevel.cache.l2.redis.cache-null-values` | Phase 2 |
| `caches.<name>.local-ttl` | `caches.<name>.l1-ttl` | Phase 2 |
| `caches.<name>.distributed-ttl` | `caches.<name>.l2-ttl` | Phase 2 |

Old properties are accepted until version 2.0. A startup `WARN` log is emitted when deprecated
properties are detected.

---

## Delivery summary

| Phase | Scope | Effort | Breaks existing API? |
|-------|-------|--------|----------------------|
| 1 — Provider SPI | Introduce interfaces; refactor internals | Medium | No |
| 2 — Guava + HashMap L1 | Two new L1 providers + config | Small | No |
| 3 — Memcached L2 | New L2 provider + broker decoupling | Large | No |
| 4 — Single-tier mode | Null providers + config | Small | No |
| 5 — Custom beans | Javadoc + `@ConditionalOnMissingBean` wiring | Small | No |

No phase breaks the existing API or test suite. Each phase is independently shippable.

---

## Open questions

1. **Invalidation without pub/sub (Phase 3):** Should `clear()` on a Memcached-backed cache
   silently no-op L2, or should it throw? A silent no-op is safer but could mask bugs.
   Recommendation: no-op with a prominent `WARN` log and documentation note.

2. **Metrics for non-Caffeine providers:** Caffeine exposes rich stats (`hitRate`, `loadCount`, etc.).
   Guava exposes similar stats. HashMap has none. Should `LocalCacheStats` define a minimal
   contract (hit/miss counts only) or expose the full Caffeine API?
   Recommendation: minimal contract (hit count, miss count, eviction count, size);
   providers that support more can expose it via a typed extension interface.

3. **HashMap TTL eviction thread:** A background sweeper adds complexity and a thread to the
   runtime. Alternative: lazy expiry checked on `getIfPresent`. This is simpler but means
   expired entries occupy memory until they are accessed.
   Recommendation: lazy expiry for `hashmap` provider; document the trade-off.

4. **InvalidationBroker lifecycle:** The broker runs a pub/sub subscription thread.
   Who owns its `start()` / `stop()` lifecycle — the broker itself, the `CacheManager`, or
   the Spring `ApplicationContext`?
   Recommendation: broker implements `SmartLifecycle`; Spring manages the lifecycle.
