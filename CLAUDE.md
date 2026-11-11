# CLAUDE.md — Multilevel Cache Library

## Project Overview

A Spring Boot auto-configuration library for two-tier caching: Caffeine (L1, JVM-local, ~1 ms)
in front of Redis (L2, distributed, ~5–10 ms), with automatic cross-instance L1 invalidation
via Redis pub/sub.

**Artifact coordinates:** `io.towersofhanoi:towers-of-hanoi:1.0.0`  
**Package root:** `io.towersofhanoi`  
**Build:** Gradle 8.2, Java 17, Spring Boot BOM 3.2.5

---

## Repository Layout

```
src/main/java/io/towersofhanoi/
  autoconfigure/   MultiLevelCacheAutoConfiguration   — Spring Boot SPI entry point
  config/          MultiLevelCacheProperties           — @ConfigurationProperties (prefix: multilevel.cache)
                   CacheConfig                         — per-cache override model
  core/            MultiLevelCache                     — implements Spring Cache; L1 + L2 logic
                   MultiLevelCacheManager              — implements CacheManager; lazy cache creation
                   NullValueMarker                     — sentinel for nullable cache entries
  invalidation/    CacheInvalidationEvent              — pub/sub message payload
                   CacheInvalidationPublisher          — PUBLISH to Redis channel on evict/clear
                   CacheInvalidationListener           — SUBSCRIBE; calls evictLocalOnly() on receipt
  serialization/   GenericJacksonRedisSerializer       — embeds @class type metadata in JSON

src/test/java/io/towersofhanoi/
  MultiLevelCacheUnitTest        — 12 Mockito unit tests
  MultiLevelCacheIntegrationTest — 7 Testcontainers integration tests (redis:7-alpine)
  TestApplication / TestCacheService — minimal Spring Boot test harness
```

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `@AutoConfiguration(before=CacheAutoConfiguration, after=RedisAutoConfiguration)` | Wins the `CacheManager` election; Redis connection factory is ready |
| `@ConditionalOnMissingBean` on every registered bean | Consumers can override any component without forking |
| All Spring/Redis/Micrometer deps are `compileOnly` | Zero forced transitive deps; consumers bring their own versions |
| `MeterRegistry` injected by type, not `Object` | Avoids `NoUniqueBeanDefinitionException` matching all beans |
| Shorter L1 TTL than L2 TTL | L1 expires first, forcing periodic L2 refreshes |
| `evictLocalOnly()` separate from `evict()` | Prevents the invalidation listener from re-publishing, avoiding pub/sub loops |
| `NullValueMarker` sentinel (non-final) | Allows nullable caching via Redis; default is `cacheNullValues=false` |
| `@JsonIgnore` on `CacheInvalidationEvent.isFullClear()` | Prevents Jackson serializing the computed boolean as a JSON field |
| `-parameters` compiler flag | Required for Spring SpEL `key="#id"` expressions |
| `clear()` uses `keys("name:*")` | **Known limitation** — O(N), blocks Redis; documented, to be replaced with SCAN |

---

## SPI Registration

```
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  → io.towersofhanoi.autoconfigure.MultiLevelCacheAutoConfiguration
```

---

## Configuration Reference

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
    caches:
      products:
        local-maximum-size: 50000
        local-ttl: 2m
        distributed-ttl: 30m
```

---

## Known Bugs (tracked in GITHUB_REPO_PLAN.md Phase 2)

1. **`clear()` uses `keys()`** — `MultiLevelCache.clear()` calls `redisTemplate.keys("name:*")`, which is O(N) and blocks Redis. Must be replaced with cursor-based SCAN.
2. **Thundering herd in `get(key, Callable)`** — does not use Caffeine's lock-striped `get(key, mappingFunction)`; concurrent misses for the same key each invoke the loader.
3. **`putIfAbsent` not overridden** — defaults to Spring's unatomic `get + put` across two tiers.
4. **Dual `ObjectMapper` instances** — `CacheInvalidationPublisher` and `CacheInvalidationListener` each create their own `ObjectMapper`; should share one injected instance.

---

## Invariants — Never Break These

- All beans in `MultiLevelCacheAutoConfiguration` must remain `@ConditionalOnMissingBean`.
- L1 and L2 operations in `MultiLevelCache` must stay inside try/catch for graceful degradation.
- `evictLocalOnly()` and `clearLocalOnly()` must never call `invalidationPublisher`; calling them from the listener would create a pub/sub event loop.
- The `-parameters` compiler flag in `build.gradle.kts` must not be removed.

---

## Testing

```bash
# All tests (requires Docker for Testcontainers)
./gradlew test

# Unit tests only (no Docker)
./gradlew test --tests "io.towersofhanoi.MultiLevelCacheUnitTest"

# Integration tests only
./gradlew test --tests "io.towersofhanoi.MultiLevelCacheIntegrationTest"

# Full check (build + test + style)
./gradlew check
```

Tests use `redis:7-alpine` via Testcontainers. The `@DynamicPropertySource` injects the mapped
host/port into Spring context before the application starts.

---

## Roadmap Documents

| File | Contents |
|------|----------|
| `GITHUB_REPO_PLAN.md` | Full gap analysis + 7-phase plan to world-class open-source library |
| `SPRING-BOOT-STARTER.md` | Multi-module restructure plan (autoconfigure + starter + BOM) |
| `EXTENSIBILITY.md` | Provider SPI roadmap (pluggable L1/L2/InvalidationBroker) |

---

## Publishing

Currently published to local Maven only:
```bash
./gradlew publishToMavenLocal
```

Maven Central publishing is planned in Phase 3–4 of `GITHUB_REPO_PLAN.md`.
Required POM additions before Central submission: `url`, `developers`, `scm`.
