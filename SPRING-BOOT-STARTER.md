# How to Provide a Spring Boot Starter for Towers of Hanoi

A Spring Boot starter is a two-module pattern that lets consumers add one dependency and get
everything wired automatically. This document explains the convention, how to restructure
the current single-module library into a proper starter pair, and how to publish and test it.

---

## Contents

- [Why a starter?](#why-a-starter)
- [The two-module convention](#the-two-module-convention)
- [Current vs target structure](#current-vs-target-structure)
- [Module 1 — autoconfigure](#module-1--autoconfigure)
- [Module 2 — starter](#module-2--starter)
- [Root build file](#root-build-file)
- [SPI and metadata registration](#spi-and-metadata-registration)
- [Optional: BOM module](#optional-bom-module)
- [Testing the starter](#testing-the-starter)
- [Publishing](#publishing)
- [Naming rules](#naming-rules)
- [Spring Boot 2.x compatibility](#spring-boot-2x-compatibility)

---

## Why a starter?

Right now a consumer must declare three dependencies to use this library:

```kotlin
implementation("io.towersofhanoi:towers-of-hanoi:1.0.0")
implementation("org.springframework.boot:spring-boot-starter-cache")
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

With a starter, that collapses to one line:

```kotlin
implementation("io.towersofhanoi:towers-of-hanoi-spring-boot-starter:1.0.0")
```

Spring Boot discovers and applies the auto-configuration automatically. The consumer only
needs to add `@EnableCaching` and optional YAML properties.

---

## The two-module convention

Spring Boot's own documentation mandates two distinct modules for third-party starters:

```
{name}-spring-boot-autoconfigure
    ↑ contains the @AutoConfiguration class, @ConfigurationProperties,
      and all conditional bean definitions.
      No hard runtime dependencies on Spring Boot — only compileOnly.

{name}-spring-boot-starter
    ↑ an almost-empty module whose sole job is to declare the right
      runtime dependencies so they land on the consumer's classpath.
```

The autoconfigure module is independently useful (non-Spring-Boot apps can wire it
manually). The starter is the convenience entry point for Spring Boot apps.

> **Naming rule:** Third-party starters must NOT use the `spring-boot-starter-*` prefix.
> That namespace is reserved for official Spring starters.
> Use `{your-name}-spring-boot-starter` instead.

---

## Current vs target structure

### Current (single module)

```
towers-of-hanoi/
└── src/
    └── main/java/io/towersofhanoi/
        ├── autoconfigure/   MultiLevelCacheAutoConfiguration
        ├── config/          MultiLevelCacheProperties, CacheConfig
        ├── core/            MultiLevelCache, MultiLevelCacheManager, NullValueMarker
        ├── invalidation/    CacheInvalidationEvent, Publisher, Listener
        └── serialization/   GenericJacksonRedisSerializer
```

### Target (two modules)

```
towers-of-hanoi/                                  ← root (no source)
├── settings.gradle.kts
├── build.gradle.kts                              ← shared config only
│
├── towers-of-hanoi-spring-boot-autoconfigure/    ← module 1
│   ├── build.gradle.kts
│   └── src/main/java/io/towersofhanoi/
│       ├── autoconfigure/
│       ├── config/
│       ├── core/
│       ├── invalidation/
│       └── serialization/
│
└── towers-of-hanoi-spring-boot-starter/          ← module 2
    ├── build.gradle.kts
    └── src/main/java/io/towersofhanoi/starter/
        └── package-info.java                     ← optional, keeps jar non-empty
```

---

## Module 1 — autoconfigure

This module is essentially the current library minus the opinionated runtime dependencies.

### build.gradle.kts

```kotlin
plugins {
    id("java-library")
    id("io.spring.dependency-management")
    id("maven-publish")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.5")
    }
}

dependencies {
    // Core cache abstraction — always needed
    api("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Spring Boot — compileOnly so we don't force a version on consumers
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-context-support")
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")
    compileOnly("io.micrometer:micrometer-core")

    // Jackson for Redis serialization — runtime dep, always needed
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Generates META-INF/spring-configuration-metadata.json for IDE support
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}
```

### What it must contain

| File | Purpose |
|------|---------|
| All current `io.towersofhanoi.**` classes | The implementation |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Spring Boot 3.x SPI registration |
| `META-INF/spring.factories` | Spring Boot 2.x compatibility (see below) |
| `META-INF/spring-configuration-metadata.json` | Generated by annotation processor; powers IDE autocompletion for `multilevel.cache.*` |
| `META-INF/additional-spring-configuration-metadata.json` | Optional: hand-written metadata for properties the processor can't infer |

### AutoConfiguration.imports (Spring Boot 3.x)

```
# src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
io.towersofhanoi.autoconfigure.MultiLevelCacheAutoConfiguration
```

### spring.factories (Spring Boot 2.x — optional)

```properties
# src/main/resources/META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  io.towersofhanoi.autoconfigure.MultiLevelCacheAutoConfiguration
```

Including both files makes the autoconfigure module compatible with Spring Boot 2.x and 3.x
simultaneously without branching.

---

## Module 2 — starter

This module contains almost no code. Its only job is to pull in the right runtime dependencies.

### build.gradle.kts

```kotlin
plugins {
    id("java-library")
    id("io.spring.dependency-management")
    id("maven-publish")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.5")
    }
}

dependencies {
    // The autoconfigure module
    api(project(":towers-of-hanoi-spring-boot-autoconfigure"))

    // Runtime deps every consumer needs — these are what justify the starter
    api("org.springframework.boot:spring-boot-starter")          // core Spring Boot
    api("org.springframework.boot:spring-boot-starter-cache")    // @EnableCaching infrastructure
    api("org.springframework.boot:spring-boot-starter-data-redis") // Lettuce + RedisTemplate

    // Caffeine is already pulled in transitively via autoconfigure (api dep),
    // but declaring it here documents the intent explicitly.
}
```

### Why api() not implementation()?

Using `api()` means these dependencies are transitively visible to consumers of the starter.
That is intentional — a consumer who calls `@Cacheable` needs `spring-context-support` on
their compile classpath, and they should not have to declare it themselves.

### Optional: package-info.java

Gradle will warn if you publish a jar with no classes. Add a minimal file to suppress it:

```java
// src/main/java/io/towersofhanoi/starter/package-info.java
/**
 * Spring Boot starter for Towers of Hanoi multi-level cache.
 * Add this dependency to get zero-config two-tier Caffeine + Redis caching.
 */
@NonNullApi
package io.towersofhanoi.starter;

import org.springframework.lang.NonNullApi;
```

---

## Root build file

The root `build.gradle.kts` manages shared configuration across both submodules.

### settings.gradle.kts

```kotlin
rootProject.name = "towers-of-hanoi"

include(
    "towers-of-hanoi-spring-boot-autoconfigure",
    "towers-of-hanoi-spring-boot-starter"
)
```

### build.gradle.kts (root)

```kotlin
plugins {
    id("io.spring.dependency-management") version "1.1.4" apply false
    id("maven-publish") apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "maven-publish")

    group = "io.towersofhanoi"
    version = "1.0.0"

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        withJavadocJar()
        withSourcesJar()
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                pom {
                    url.set("https://github.com/your-org/towers-of-hanoi")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                }
            }
        }
    }
}
```

---

## SPI and metadata registration

### Configuration metadata for IDE support

The `spring-boot-configuration-processor` annotation processor generates
`META-INF/spring-configuration-metadata.json` at compile time by reading
`@ConfigurationProperties` classes. This powers autocompletion in IntelliJ and VS Code.

For properties the processor cannot infer (e.g. map keys, enum descriptions), add a
hand-written supplement:

```json
// src/main/resources/META-INF/additional-spring-configuration-metadata.json
{
  "hints": [
    {
      "name": "multilevel.cache.l1.provider",
      "values": [
        { "value": "caffeine",  "description": "Caffeine in-process cache (default)" },
        { "value": "guava",     "description": "Guava in-process cache" },
        { "value": "hashmap",   "description": "ConcurrentHashMap — no size bound, lazy TTL" },
        { "value": "none",      "description": "Disable L1; use L2 only" }
      ]
    },
    {
      "name": "multilevel.cache.l2.provider",
      "values": [
        { "value": "redis",      "description": "Redis distributed cache (default)" },
        { "value": "memcached",  "description": "Memcached distributed cache" },
        { "value": "none",       "description": "Disable L2; use L1 only" }
      ]
    }
  ]
}
```

### Autoconfigure ordering

`MultiLevelCacheAutoConfiguration` already carries the correct ordering annotations:

```java
@AutoConfiguration(
    before  = CacheAutoConfiguration.class,   // win the CacheManager election
    after   = RedisAutoConfiguration.class    // Redis connection factory is ready
)
```

No changes needed when moving to the multi-module layout.

---

## Optional: BOM module

For consumers who want to import just the autoconfigure module (not the full starter),
a Bill of Materials simplifies version management across both artifacts.

### towers-of-hanoi-bom/build.gradle.kts

```kotlin
plugins {
    id("java-platform")
    id("maven-publish")
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api("io.towersofhanoi:towers-of-hanoi-spring-boot-autoconfigure:${version}")
        api("io.towersofhanoi:towers-of-hanoi-spring-boot-starter:${version}")
    }
}
```

Consumer usage:

```kotlin
// import the BOM, then reference artifacts without versions
implementation(platform("io.towersofhanoi:towers-of-hanoi-bom:1.0.0"))
implementation("io.towersofhanoi:towers-of-hanoi-spring-boot-starter")
```

---

## Testing the starter

### Integration test in the autoconfigure module

The existing `MultiLevelCacheIntegrationTest` lives in the autoconfigure module.
It bootstraps via `@SpringBootTest(classes = TestApplication.class)` with Testcontainers Redis.
No changes needed.

### Smoke test in the starter module

Add a minimal test to the starter module that verifies the starter's transitive dependency
graph is complete — i.e. a bare `@SpringBootTest` with no extra imports compiles and boots:

```java
// towers-of-hanoi-spring-boot-starter/src/test/java/io/towersofhanoi/starter/StarterSmokeTest.java
@SpringBootTest(classes = StarterSmokeTest.TestApp.class)
@Testcontainers
class StarterSmokeTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    CacheManager cacheManager;

    @Test
    void cacheManagerIsMultiLevel() {
        assertThat(cacheManager).isInstanceOf(MultiLevelCacheManager.class);
    }

    @SpringBootApplication
    @EnableCaching
    static class TestApp {}
}
```

This test proves the starter's dependency descriptor is complete: if any transitive dep is
missing, the context will fail to load.

---

## Publishing

### Local Maven repository (development)

```bash
./gradlew publishToMavenLocal
```

Publishes both modules to `~/.m2/repository/io/towersofhanoi/`.

### Maven Central via Sonatype OSSRH

1. Register at [central.sonatype.com](https://central.sonatype.com)
2. Add the `signing` and `nexus-publish` plugins to the root build
3. Configure GPG signing key in `~/.gradle/gradle.properties`:

```properties
signing.keyId=ABCD1234
signing.password=your-passphrase
signing.secretKeyRingFile=/path/to/secring.gpg
sonatypeUsername=your-username
sonatypePassword=your-token
```

4. Add to root `build.gradle.kts`:

```kotlin
subprojects {
    apply(plugin = "signing")

    configure<SigningExtension> {
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}
```

5. Publish:

```bash
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```

### GitHub Packages (simpler alternative)

```kotlin
configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/your-org/towers-of-hanoi")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

---

## Naming rules

| ✅ Correct | ❌ Incorrect |
|-----------|-------------|
| `towers-of-hanoi-spring-boot-starter` | `spring-boot-starter-towers-of-hanoi` |
| `towers-of-hanoi-spring-boot-autoconfigure` | `towers-of-hanoi-autoconfigure-spring-boot` |
| group `io.towersofhanoi` | group `org.springframework` |

The `spring-boot-starter-*` prefix is reserved for starters maintained by the Spring team.
Using it for a third-party artifact causes confusion and may be rejected by Maven Central reviewers.

---

## Spring Boot 2.x compatibility

Spring Boot 3.x uses `AutoConfiguration.imports`. Spring Boot 2.x uses `spring.factories`.
To support both from the same jar, include both files:

```
src/main/resources/
└── META-INF/
    ├── spring/
    │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports   ← 3.x
    └── spring.factories                                                        ← 2.x
```

`spring.factories` content:
```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  io.towersofhanoi.autoconfigure.MultiLevelCacheAutoConfiguration
```

Spring Boot 3.x ignores `spring.factories` for `EnableAutoConfiguration` entries (it was
deprecated in 2.7 and removed from processing in 3.0). Spring Boot 2.7+ processes both.
So having both files is safe and provides the widest compatibility window.

---

## Summary checklist

- [ ] Split into `towers-of-hanoi-spring-boot-autoconfigure` and `towers-of-hanoi-spring-boot-starter`
- [ ] Move all source classes and resources into the autoconfigure module
- [ ] autoconfigure module: all Spring/Redis/Micrometer deps are `compileOnly`
- [ ] starter module: all consumer-facing deps are `api()`
- [ ] `AutoConfiguration.imports` present in autoconfigure resources
- [ ] `spring.factories` present for Spring Boot 2.x support
- [ ] `spring-boot-configuration-processor` annotation processor in autoconfigure module
- [ ] `additional-spring-configuration-metadata.json` for IDE enum hints
- [ ] Smoke test in starter module validates transitive dependency graph
- [ ] Both modules published with sources and javadoc jars
- [ ] (Optional) BOM module for consumers who want version-free imports

---

## Phased plan to deliver the starter

The following phases take the current single-module library to a fully published,
production-grade Spring Boot starter. Each phase is independently shippable and
does not break the previous phase's consumers.

---

### Phase 1 — Multi-module restructure

**Goal:** Convert the single Gradle module into the two-module layout without changing
any library behaviour. All 19 existing tests must stay green at the end of this phase.

**Steps:**

1. Update `settings.gradle.kts` to declare two subprojects:
   ```
   include("towers-of-hanoi-spring-boot-autoconfigure")
   include("towers-of-hanoi-spring-boot-starter")
   ```

2. Create `towers-of-hanoi-spring-boot-autoconfigure/` and move all source files into it:
   ```
   src/main/java/io/towersofhanoi/**
   src/test/java/io/towersofhanoi/**
   src/main/resources/META-INF/**
   ```

3. Create `towers-of-hanoi-spring-boot-starter/` with an empty `build.gradle.kts` that
   declares `api(project(":towers-of-hanoi-spring-boot-autoconfigure"))`.

4. Move shared Gradle config (Java version, compiler flags, publishing boilerplate)
   into the root `build.gradle.kts` under a `subprojects {}` block.

5. Run `./gradlew build` — all tests pass, both modules produce jars.

**Exit criteria:**
- `towers-of-hanoi-spring-boot-autoconfigure-1.0.0.jar` exists
- `towers-of-hanoi-spring-boot-starter-1.0.0.jar` exists
- All 19 tests green

**Breaks existing consumers?** No — the autoconfigure jar is identical to the old jar.

---

### Phase 2 — Harden the autoconfigure module

**Goal:** Make the autoconfigure module safe to embed in any Spring project, not just
Spring Boot apps. All runtime deps that belong to the consumer must be `compileOnly`.

**Steps:**

1. Audit `towers-of-hanoi-spring-boot-autoconfigure/build.gradle.kts`:
   - `spring-boot-autoconfigure` → `compileOnly`
   - `spring-boot-starter-data-redis` → `compileOnly`
   - `micrometer-core` → `compileOnly`
   - `caffeine` → `api` (always required, not optional)
   - `jackson-databind` + `jackson-datatype-jsr310` → `implementation`

2. Add `spring-boot-configuration-processor` as `annotationProcessor`. Verify that
   `META-INF/spring-configuration-metadata.json` is generated under
   `build/classes/java/main/META-INF/` after `./gradlew compileJava`.

3. Add `src/main/resources/META-INF/additional-spring-configuration-metadata.json`
   with enum hints for `l1.provider` and `l2.provider` (see [SPI and metadata registration](#spi-and-metadata-registration)).

4. Add `src/main/resources/META-INF/spring.factories` for Spring Boot 2.x compatibility
   (see [Spring Boot 2.x compatibility](#spring-boot-2x-compatibility)).

5. Verify the generated metadata appears in IntelliJ:
   open a consumer project, type `multilevel.cache.` in `application.yml` —
   completions must appear.

**Exit criteria:**
- `mvn dependency:tree` on the autoconfigure jar shows no Spring Boot runtime deps
- `spring-configuration-metadata.json` present in published jar
- IDE autocompletion works for all `multilevel.cache.*` properties

**Breaks existing consumers?** No.

---

### Phase 3 — Wire the starter's dependency descriptor

**Goal:** Add the runtime dependencies to the starter module so a consumer can adopt
the library with a single `implementation(...)` line.

**Steps:**

1. Populate `towers-of-hanoi-spring-boot-starter/build.gradle.kts` with `api()` deps:
   ```kotlin
   api(project(":towers-of-hanoi-spring-boot-autoconfigure"))
   api("org.springframework.boot:spring-boot-starter")
   api("org.springframework.boot:spring-boot-starter-cache")
   api("org.springframework.boot:spring-boot-starter-data-redis")
   ```

2. Add `src/main/java/io/towersofhanoi/starter/package-info.java` to keep the jar
   non-empty (Gradle warns on empty jars).

3. Write the starter smoke test (`StarterSmokeTest`) described in
   [Testing the starter](#testing-the-starter). It must pass with only the starter on
   the test classpath — no direct autoconfigure dependency.

4. Verify the dependency graph with:
   ```bash
   ./gradlew towers-of-hanoi-spring-boot-starter:dependencies --configuration runtimeClasspath
   ```
   Confirm `spring-boot-starter-cache`, `spring-boot-starter-data-redis`, and the
   autoconfigure module all appear.

**Exit criteria:**
- Smoke test passes
- `./gradlew publishToMavenLocal` produces a resolvable starter artifact
- A scratch project that declares only `implementation("io.towersofhanoi:towers-of-hanoi-spring-boot-starter:1.0.0-SNAPSHOT")` boots successfully

**Breaks existing consumers?** No.

---

### Phase 4 — Add a BOM module

**Goal:** Allow consumers to import both artifacts version-free using a Bill of Materials,
following the same pattern as `spring-boot-dependencies`.

**Steps:**

1. Add `towers-of-hanoi-bom` to `settings.gradle.kts`:
   ```kotlin
   include("towers-of-hanoi-bom")
   ```

2. Create `towers-of-hanoi-bom/build.gradle.kts` with `java-platform` plugin
   (see [Optional: BOM module](#optional-bom-module)).

3. Add constraints for both artifacts at the current version.

4. Write a BOM consumer test: a Gradle project that imports the BOM and resolves
   the starter without an explicit version — confirm it resolves to the correct version.

5. Publish the BOM to local Maven:
   ```bash
   ./gradlew towers-of-hanoi-bom:publishToMavenLocal
   ```

**Exit criteria:**
- Consumer project using `platform("io.towersofhanoi:towers-of-hanoi-bom:1.0.0")` resolves
  `towers-of-hanoi-spring-boot-starter` without a version string

**Breaks existing consumers?** No — BOM is opt-in.

---

### Phase 5 — CI pipeline and release automation

**Goal:** Every merge to `main` publishes a snapshot. Every Git tag triggers a release.

**Steps:**

1. Add a GitHub Actions workflow `.github/workflows/ci.yml`:
   ```yaml
   on: [push, pull_request]
   jobs:
     build:
       runs-on: ubuntu-latest
       services:
         redis:
           image: redis:7-alpine
           ports: ["6379:6379"]
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with: { java-version: '17', distribution: 'corretto' }
         - run: ./gradlew build
   ```

2. Add a release workflow `.github/workflows/release.yml` triggered on `push` to tags
   matching `v*.*.*`. It runs `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository`
   using secrets for the GPG key and Sonatype credentials.

3. Configure GPG signing in the root build (see [Publishing](#publishing)).

4. Verify the release workflow end-to-end by pushing a `v1.0.0` tag to a test repository
   and confirming the artifacts appear in Sonatype staging.

**Exit criteria:**
- Pushing `git tag v1.0.0 && git push --tags` triggers a full release to Maven Central staging
- Both modules (`autoconfigure`, `starter`) appear in staging with sources, javadoc, POM, and `.asc` signatures
- BOM module also published

**Breaks existing consumers?** No.

---

### Phase 6 — Validation with a real consumer project

**Goal:** Confirm the published starter works exactly as documented from a clean slate,
with no prior knowledge of the library internals.

**Steps:**

1. Create a brand-new Spring Boot project at [start.spring.io](https://start.spring.io)
   with only `Spring Web` and `Spring Cache` selected.

2. Add a single dependency to the generated `build.gradle.kts`:
   ```kotlin
   implementation("io.towersofhanoi:towers-of-hanoi-spring-boot-starter:1.0.0")
   ```

3. Add `@EnableCaching` to the main class. Add a `@Cacheable`-annotated service method.
   Add `spring.data.redis.*` properties pointing at a local Redis.

4. Start the application. Verify:
   - `MultiLevelCacheManager` is active (check `/actuator/beans`)
   - First call loads from origin; second call is served from L1 (Caffeine hit in metrics)
   - Metrics appear at `/actuator/metrics/cache.gets`

5. Document any friction encountered as issues to fix before the public announcement.

**Exit criteria:**
- A developer with no prior knowledge of the library can adopt it in under 10 minutes
  following only the README Quick Start section
- No undocumented steps required

---

### Phase summary

| Phase | Goal | Consumer impact | Effort |
|-------|------|-----------------|--------|
| 1 — Multi-module restructure | Split into two Gradle modules | None | Small |
| 2 — Harden autoconfigure | Clean dep graph, IDE metadata, 2.x compat | None | Small |
| 3 — Wire starter deps | Single-dependency adoption | Additive | Small |
| 4 — BOM module | Version-free imports | Additive | Small |
| 5 — CI and release pipeline | Automated publishing on tag | None | Medium |
| 6 — Consumer validation | End-to-end adoption test | None | Small |

Each phase ends with a shippable artifact. Phases 1–3 are the minimum viable starter.
Phases 4–6 bring it to production-grade, publicly consumable quality.
