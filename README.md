# Towers of Hanoi — Spring Boot Cache

A production-grade, two-tier caching library for Spring Boot. Keeps hot data in a local Caffeine cache (~1 ms) and falls back to a shared Redis cache (~5–10 ms), with automatic cross-instance invalidation and zero-config Spring Boot integration.

```
Request → L1 (Caffeine, JVM-local) → L2 (Redis, distributed) → Database
              ~1 ms                       ~5–10 ms                ~50–100 ms
```

---

## Contents

- [Performance characteristics](#performance-characteristics)
- [Quick start](#quick-start)
- [Configuration reference](#configuration-reference)
- [Per-cache overrides](#per-cache-overrides)
- [Cross-instance invalidation](#cross-instance-invalidation)
- [Metrics](#metrics)
- [Customisation hooks](#customisation-hooks)
- [Architecture](#architecture)
- [Design decisions](#design-decisions)
- [Building from source](#building-from-source)

---

## Performance characteristics

| Layer | Latency | Scope | Storage |
|-------|---------|-------|---------|
| L1 — Caffeine | ~1 ms | Single JVM instance | JVM heap |
| L2 — Redis | ~5–10 ms | All instances (shared) | Separate Redis node |
| Origin (DB / API) | ~50–100 ms | — | — |

---

## Quick start

### 1. Add the dependency

**Gradle (Kotlin DSL)**
```kotlin
dependencies {
    implementation("io.towersofhanoi:towers-of-hanoi:1.0.0")
}
```

**Maven**
```xml
<dependency>
    <groupId>io.towersofhanoi</groupId>
    <artifactId>towers-of-hanoi</artifactId>
    <version>1.0.0</version>
</dependency>
```

The library requires the following on the classpath at runtime (bring your own versions):

| Dependency | Minimum version |
|-----------|----------------|
| `spring-boot-starter-data-redis` | 3.0 |
| `spring-boot-starter-cache` | 3.0 |
| Java | 17 |

### 2. Enable caching

```java
@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

That is the only change needed. The auto-configuration registers `MultiLevelCacheManager` as the active `CacheManager` when both Caffeine and Spring Data Redis are on the classpath.

### 3. Annotate your service

```java
@Service
public class ProductService {

    @Cacheable(value = "products", key = "#id")
    public Product findById(String id) {
        return repository.findById(id).orElseThrow();
    }

    @CachePut(value = "products", key = "#product.id")
    public Product save(Product product) {
        return repository.save(product);
    }

    @CacheEvict(value = "products", key = "#id")
    public void delete(String id) {
        repository.deleteById(id);
    }
}
```

> **Important:** The `-parameters` compiler flag is required for Spring SpEL expressions that reference parameter names (e.g. `key = "#id"`). The library's own build already sets this flag; ensure your application's build does too.
>
> **Gradle:** `options.compilerArgs.add("-parameters")`  
> **Maven:** `<compilerArg>-parameters</compilerArg>`

---

## Configuration reference

All properties are under the `multilevel.cache` prefix. Every property has a sensible default — no configuration is required to get started.

```yaml
multilevel:
  cache:
    enabled: true                    # Set to false to disable the entire library

    local:
      maximum-size: 10000            # Max entries in Caffeine per cache
      ttl: 5m                        # Local TTL (shorter = fresher data)

    distributed:
      ttl: 1h                        # Redis TTL
      cache-null-values: false        # Whether to cache null return values

    invalidation:
      enabled: true                  # Cross-instance local cache invalidation via Redis pub/sub
      channel: multilevel:cache:invalidation  # Redis pub/sub channel name
```

### Duration format

Spring Boot parses durations from strings automatically:

| String | Meaning |
|--------|---------|
| `300s` | 300 seconds |
| `5m` | 5 minutes |
| `1h` | 1 hour |
| `PT10M` | ISO-8601: 10 minutes |

---

## Per-cache overrides

Individual caches can override any global default under `multilevel.cache.caches.<name>`:

```yaml
multilevel:
  cache:
    local:
      maximum-size: 10000
      ttl: 5m
    distributed:
      ttl: 1h
    caches:
      products:
        local-maximum-size: 50000    # Override max size for this cache only
        local-ttl: 2m
        distributed-ttl: 30m
      sessions:
        local-ttl: 1m
        distributed-ttl: 15m
        cache-null-values: true
      config:
        local-ttl: 10m
        distributed-ttl: 24h
```

Any field left null in the per-cache config falls back to the global default.

---

## Cross-instance invalidation

In a horizontally scaled deployment, each JVM maintains its own Caffeine L1 cache. Without coordination, a write on instance A updates Redis (L2) but leaves stale entries in the Caffeine caches of instances B and C.

The library solves this with a Redis pub/sub channel. When any instance evicts or updates a cache entry, it publishes a `CacheInvalidationEvent` to the configured channel. All instances — including the publisher — subscribe and evict the corresponding key from their local L1.

```
Instance A                  Redis                   Instance B
─────────────               ──────────              ─────────────
evict("products", "42") ──► DEL products:42         sub on channel
                        ──► PUBLISH channel {...} ──► evictLocalOnly("42")
```

This is enabled by default and requires no extra configuration beyond Redis connectivity.

To disable it (e.g. single-instance deployments):

```yaml
multilevel:
  cache:
    invalidation:
      enabled: false
```

---

## Metrics

When [Micrometer](https://micrometer.io) is on the classpath, the library automatically binds per-cache Caffeine statistics to the `MeterRegistry`:

| Metric | Description |
|--------|-------------|
| `cache.gets{cache="<name>", result="hit"}` | L1 hit count |
| `cache.gets{cache="<name>", result="miss"}` | L1 miss count |
| `cache.evictions{cache="<name>"}` | Eviction count |
| `cache.size{cache="<name>"}` | Estimated entry count |
| `cache.load.duration{cache="<name>"}` | Load timing |

These are visible in Spring Boot Actuator at `/actuator/metrics` and in your Prometheus / Grafana setup automatically.

---

## Customisation hooks

Every bean registered by the auto-configuration is `@ConditionalOnMissingBean`, so you can replace any component by declaring your own bean of the same type.

### Custom Jackson serializer

```java
@Bean
@Primary
public GenericJacksonRedisSerializer multiLevelCacheSerializer() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.activateDefaultTyping(
        BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.yourcompany.")   // restrict to your packages
            .build(),
        ObjectMapper.DefaultTyping.NON_FINAL
    );
    return new GenericJacksonRedisSerializer(mapper);
}
```

### Custom Redis template

```java
@Bean(name = "multiLevelCacheRedisTemplate")
public RedisTemplate<String, Object> multiLevelCacheRedisTemplate(
        RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    // configure your serializers
    template.afterPropertiesSet();
    return template;
}
```

### Custom cache manager

```java
@Bean
public MultiLevelCacheManager multiLevelCacheManager(
        MultiLevelCacheProperties properties,
        @Qualifier("multiLevelCacheRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
    // wire with your own invalidation publisher, metrics registry, etc.
    return new MultiLevelCacheManager(properties, redisTemplate, null, null);
}
```

---

## Architecture

```
┌────────────────────────────────────────────────────┐
│               Spring @Cacheable / @CacheEvict       │
└──────────────────────────┬─────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────┐
│                 MultiLevelCacheManager               │
│  Creates and owns MultiLevelCache instances lazily.  │
│  Merges global config with per-cache overrides.      │
└──────────────────────────┬───────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────┐
│                   MultiLevelCache                    │
│                                                      │
│  get(key)                                            │
│    1. L1 hit?  ──► return immediately                │
│    2. L2 hit?  ──► back-fill L1, return              │
│    3. miss     ──► caller loads, write-through both  │
│                                                      │
│  put(key, value) ──► L1 + L2 simultaneously          │
│  evict(key)      ──► L1 + L2 + pub/sub broadcast     │
└──────────────────────────┬───────────────────────────┘
                           │
           ┌───────────────┴────────────────┐
           ▼                                ▼
┌──────────────────┐             ┌──────────────────────┐
│  Caffeine (L1)   │             │     Redis (L2)        │
│  maximumSize     │             │  GenericJackson       │
│  expireAfterWrite│             │  Serializer           │
│  recordStats()   │             │  TTL via SETEX        │
└──────────────────┘             └──────────────────────┘
                                          │
                           ┌──────────────┴──────────────┐
                           ▼                             ▼
              ┌────────────────────┐       ┌─────────────────────────┐
              │ Invalidation       │       │ Invalidation             │
              │ Publisher          │       │ Listener                 │
              │ (this instance)    │       │ (all peer instances)     │
              │ PUBLISH channel    │       │ evictLocalOnly(key)      │
              └────────────────────┘       └─────────────────────────┘
```

### Serialisation

Values stored in Redis are serialised with a custom `GenericJacksonRedisSerializer` that embeds type metadata (`@class`) in the JSON payload. This allows arbitrary domain objects to be deserialised without compile-time type knowledge — no need to register types upfront.

```json
["com.example.Product", {"id": "42", "name": "Widget", "price": 9.99}]
```

Keys are plain strings: `<cacheName>:<key>` (e.g. `products:42`).

### Null value handling

By default (`cache-null-values: false`), null return values are silently skipped — the entry is not stored and the method will be called again on every cache miss. This prevents Redis bloat from nullable queries.

When `cache-null-values: true`, null values are stored using an internal `NullValueMarker` sentinel that serialises correctly through Redis and back.

### Graceful degradation

All Redis operations are wrapped in try/catch. If Redis is unavailable:
- **Reads** degrade to L1-only (or origin if L1 also misses)
- **Writes** succeed to L1; Redis failures are logged at `WARN` level
- **The application continues to serve traffic**

---

## Design decisions

| Decision | Rationale |
|----------|-----------|
| Write-through to both layers on `put` | Consistency: a Redis-evicted entry is re-served from L1 without a source call |
| Shorter L1 TTL than L2 TTL | L1 expires first, forcing periodic L2 refreshes rather than serving indefinitely stale data |
| `evictLocalOnly()` separate from `evict()` | Prevents the invalidation listener from re-publishing events it just received, avoiding infinite loops |
| `compileOnly` for Spring / Redis / Micrometer | Zero forced transitive deps — the library adds only Caffeine and Jackson to the consumer's dependency graph |
| `@ConditionalOnMissingBean` on every bean | Consumers can override any component without forking or excluding auto-config |
| Reflection-free `MeterRegistry` wiring | `MeterRegistry` typed directly (not `Object`) to avoid Spring's `NoUniqueBeanDefinitionException` matching all beans |
| `@JsonIgnore` on `CacheInvalidationEvent.isFullClear()` | Prevents Jackson from serialising the computed boolean as a JSON field that has no matching setter |

---

## Building from source

```bash
# Requires Java 17+
./gradlew build

# Run only unit tests (no Docker required)
./gradlew test --tests "org.multilevelcache.MultiLevelCacheUnitTest"

# Run integration tests (requires Docker for Testcontainers)
./gradlew test --tests "org.multilevelcache.MultiLevelCacheIntegrationTest"

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

### Environment

| Tool | Version |
|------|---------|
| Java | 17 (corretto-17 recommended) |
| Gradle | 8.2 |
| Spring Boot BOM | 3.2.5 |
| Caffeine | 3.1.8 |

---

## Packaging as a Spring Boot Starter

To distribute this library as a first-class Spring Boot starter (single-dependency, zero-config
adoption), it needs to be restructured into two modules following Spring Boot's conventions:
`towers-of-hanoi-spring-boot-autoconfigure` and `towers-of-hanoi-spring-boot-starter`.

See [SPRING-BOOT-STARTER.md](SPRING-BOOT-STARTER.md) for the full guide: module layout,
build configuration, SPI registration, IDE metadata, BOM, testing, and publishing to Maven Central.

---

## Roadmap

The library is designed to be fully provider-agnostic in future versions. The plan allows swapping L1 to Guava or `ConcurrentHashMap`, L2 to Memcached, and the invalidation broker to Kafka or RabbitMQ — all via configuration, with zero code changes for consumers.

See [EXTENSIBILITY.md](EXTENSIBILITY.md) for the full phased design plan, interface contracts, configuration schema evolution, and backward compatibility guarantees.

---

## Licence

Apache License 2.0
