# GitHub Repository Launch Plan

A prioritized, phased plan to take this library from local project to a world-class open-source
Spring Boot caching library on Maven Central.

---

## Contents

- [Gap Analysis](#gap-analysis)
  - [Critical Bugs](#critical-bugs)
  - [Repository Hygiene](#repository-hygiene)
  - [Missing GitHub Community Files](#missing-github-community-files)
  - [CI/CD](#cicd)
  - [Maven Central Readiness](#maven-central-readiness)
  - [Test Coverage Gaps](#test-coverage-gaps)
  - [Architecture Work Planned But Unimplemented](#architecture-work-planned-but-unimplemented)
  - [Branding Consideration](#branding-consideration)
- [Phased Plan](#phased-plan)
  - [Phase 1 — Repository Foundation](#phase-1--repository-foundation)
  - [Phase 2 — Bug Fixes + Code Quality](#phase-2--bug-fixes--code-quality)
  - [Phase 3 — CI/CD Pipeline](#phase-3--cicd-pipeline)
  - [Phase 4 — Multi-Module Restructure + First Maven Central Release](#phase-4--multi-module-restructure--first-maven-central-release)
  - [Phase 5 — Test Coverage Hardening](#phase-5--test-coverage-hardening)
  - [Phase 6 — Provider SPI](#phase-6--provider-spi)
  - [Phase 7 — Ecosystem and Growth](#phase-7--ecosystem-and-growth)
- [Effort Summary](#effort-summary)

---

## Gap Analysis

### Critical Bugs

Correctness issues that should be fixed before anyone builds on top of this library.

| # | Location | Issue |
|---|----------|-------|
| 1 | `MultiLevelCache.clear()` | `redisTemplate.keys("name:*")` is O(N) on all keys and **blocks the Redis event loop**. Must be replaced with cursor-based `SCAN`. |
| 2 | `MultiLevelCache.get(key, Callable)` | Calls `valueLoader.call()` without synchronization. Concurrent misses for the same key each invoke the loader independently (**thundering herd**). Should delegate to Caffeine's `get(key, mappingFunction)` which provides lock-striped, single-flight loading. |
| 3 | `MultiLevelCache` | `putIfAbsent` is not overridden. The default Spring implementation does an unatomic `get + put` across two tiers. |
| 4 | `CacheInvalidationPublisher` / `CacheInvalidationListener` | Each creates its own `ObjectMapper` instance. If one is customized, the other won't match. A single shared instance should be injected via constructor and registered in auto-config. |
| 5 | `README.md` (Building from source section) | Test command package paths reference `org.multilevelcache.*` but the actual package is `io.towersofhanoi.*`. Anyone who copies these commands will get a build failure. |

### Repository Hygiene

Issues that must be resolved before the first `git push`.

- `.gitignore` only excludes specific files within `.idea/` — many IDE artifacts (`checkstyle-idea.xml`, `codeStyles/`, `workspace.xml`) **will be committed**. The entire `.idea/` directory must be excluded.
- `.claude/settings.local.json` is not in `.gitignore` and will be committed.
- No `.github/` directory exists.

### Missing GitHub Community Files

None of the following exist in the repository:

| File | Purpose |
|------|---------|
| `CONTRIBUTING.md` | Explains how to file issues, fork, and submit PRs |
| `CODE_OF_CONDUCT.md` | Sets community expectations (use Contributor Covenant) |
| `SECURITY.md` | Responsible disclosure policy and contact |
| `CHANGELOG.md` | Version-by-version change history |
| `.github/ISSUE_TEMPLATE/bug_report.md` | Structured bug report form |
| `.github/ISSUE_TEMPLATE/feature_request.md` | Structured feature request form |
| `.github/PULL_REQUEST_TEMPLATE.md` | PR checklist for contributors |

### CI/CD

No GitHub Actions workflows exist. Every PR must be manually verified locally.

### Maven Central Readiness

The POM block in `build.gradle.kts` is missing fields **required by Sonatype Maven Central**:

- `url` — project homepage / GitHub URL
- `developers` — at least one `<developer>` block with id, name, email
- `scm` — `connection`, `developerConnection`, `url`

Without these, Central will reject the submission. Additionally:

- No GPG signing plugin is configured
- No Sonatype OSSRH (or Central Portal) publishing plugin is wired
- Version is set to `1.0.0` — consider `0.1.0-SNAPSHOT` for the initial development cycle

### Test Coverage Gaps

| Missing test | Risk |
|-------------|------|
| Cross-instance pub/sub invalidation | The entire invalidation feature is untested end-to-end |
| `cache.clear()` behavior | The `keys()` bug above is also untested |
| Per-cache override resolution | The config override merge logic is untested |
| Metrics binding | Micrometer integration is untested |
| Graceful Redis degradation (integration) | Only unit-tested via Mockito mock; not verified against a real Redis that goes down |

### Architecture Work Planned But Unimplemented

Both `SPRING-BOOT-STARTER.md` and `EXTENSIBILITY.md` describe planned work that is not yet implemented.
These are good roadmap documents. The multi-module split (autoconfigure + starter) is what enables
single-dependency consumer adoption and should land before v1.0.

### Branding Consideration

"Towers of Hanoi" is a well-known sorting puzzle algorithm. Using it as a cache library name has two
practical consequences:

1. **Discoverability**: searches for "spring multilevel cache java" or "caffeine redis spring boot" will not surface this repository.
2. **First impression**: the connection between the puzzle and two-tier caching is non-obvious to a new visitor.

Options:
- Keep the brand but invest in GitHub topics/tags and a clear subtitle in the README to compensate.
- Rename to something self-describing: `spring-multilevel-cache`, `caffeine-redis-spring-boot-starter`, etc.

This decision should be made before the first Maven Central release since the `groupId:artifactId` is permanent.

---

## Phased Plan

### Phase 1 — Repository Foundation

**Goal:** A clean, professional repo that creates a strong first impression with zero embarrassments on day one.  
**Timing:** Complete before the first `git push`.

**Tasks:**

- [ ] Fix `.gitignore` — exclude all of `.idea/`, `.claude/`, `*.local.json`
- [ ] Fix README "Building from source" test command paths (`io.towersofhanoi.*`)
- [ ] Fix `build.gradle.kts` POM — add `url`, `developers`, `scm` fields
- [ ] Decide on versioning strategy (`0.1.0-SNAPSHOT` for development; `1.0.0` after Maven Central validation)
- [ ] Decide on branding / final artifact coordinates
- [ ] Add `CONTRIBUTING.md`
- [ ] Add `CODE_OF_CONDUCT.md` (Contributor Covenant v2)
- [ ] Add `SECURITY.md` (responsible disclosure instructions)
- [ ] Add `CHANGELOG.md` (start with `## [Unreleased]` section)
- [ ] Add `.github/ISSUE_TEMPLATE/bug_report.md`
- [ ] Add `.github/ISSUE_TEMPLATE/feature_request.md`
- [ ] Add `.github/PULL_REQUEST_TEMPLATE.md`
- [ ] Add GitHub repository topics: `spring-boot`, `cache`, `caffeine`, `redis`, `multilevel-cache`, `java`
- [ ] Add README badges: CI status, Maven Central version, license, Java version

**Exit criteria:** Repo is public, README looks polished, issues can be filed with structured templates.

---

### Phase 2 — Bug Fixes + Code Quality

**Goal:** Fix all correctness issues before anyone builds production systems on top of this library.

**Tasks:**

- [ ] Replace `keys("name:*")` with cursor-based `SCAN` in `MultiLevelCache.clear()`
- [ ] Fix `get(key, Callable)` — delegate to Caffeine's `get(key, mappingFunction)` for atomic, lock-striped loading
- [ ] Override `putIfAbsent` with explicit two-tier semantics
- [ ] Unify `ObjectMapper` between `CacheInvalidationPublisher` and `CacheInvalidationListener` — inject via constructor, register as a bean in auto-config
- [ ] Add `@NonNullApi` / `@NonNullFields` `package-info.java` annotations for null contract documentation
- [ ] Add Checkstyle (Google Java Style) and wire into `./gradlew check`

**Exit criteria:** `./gradlew check` enforces style cleanly; all five correctness issues above are resolved.

---

### Phase 3 — CI/CD Pipeline

**Goal:** Every PR is validated automatically; every version tag produces a releasable artifact.

**Tasks:**

- [ ] Add `.github/workflows/ci.yml`
  - Trigger: push + pull_request
  - Runner: `ubuntu-latest` (Docker available for Testcontainers)
  - Steps: checkout, setup Java 17 (Corretto), `./gradlew check`
- [ ] Add `.github/workflows/release.yml`
  - Trigger: push to tags matching `v*.*.*`
  - Steps: build, sign with GPG, publish to Sonatype staging, close and release
- [ ] Add GPG signing plugin to `build.gradle.kts`
- [ ] Add Sonatype OSSRH (or Central Portal) publishing plugin
- [ ] Store secrets in GitHub Actions: `GPG_KEY`, `GPG_PASSPHRASE`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`
- [ ] Add JaCoCo coverage reporting, publish as workflow artifact or to Codecov

**Exit criteria:** A PR shows automated test results; pushing `v0.1.0` publishes to Maven Central staging.

---

### Phase 4 — Multi-Module Restructure + First Maven Central Release

**Goal:** Single-dependency consumer adoption via `implementation("...starter:0.1.0")`.  
Implements the plan in [`SPRING-BOOT-STARTER.md`](SPRING-BOOT-STARTER.md).

**Tasks:**

- [ ] Update `settings.gradle.kts` to declare three subprojects
- [ ] Create `towers-of-hanoi-spring-boot-autoconfigure/` — move all source and resources into it
- [ ] Create `towers-of-hanoi-spring-boot-starter/` — single `api()` dependency on autoconfigure + Spring Boot runtime deps
- [ ] Create `towers-of-hanoi-bom/` — `java-platform` Bill of Materials
- [ ] Move shared Gradle config (Java version, compiler flags, publishing) to root `build.gradle.kts` under `subprojects {}`
- [ ] Add `spring.factories` for Spring Boot 2.7 compatibility alongside `AutoConfiguration.imports`
- [ ] Write `StarterSmokeTest` in the starter module (validates transitive dependency completeness)
- [ ] Validate end-to-end with a scratch consumer project (per SPRING-BOOT-STARTER.md Phase 6)
- [ ] Push `v0.1.0` tag — first Maven Central release

**Exit criteria:** Library resolves from Maven Central with a single dependency line; smoke test passes.

---

### Phase 5 — Test Coverage Hardening

**Goal:** Every feature has an integration test; coverage ≥ 85%.

**Tasks:**

- [ ] Add cross-instance invalidation integration test
  - Two `MultiLevelCacheManager` instances backed by the same Testcontainers Redis
  - Evict on instance A; assert L1 is cleared on instance B
- [ ] Add `cache.clear()` integration test — assert Redis keys are deleted and L1 is empty
- [ ] Add per-cache override tests — verify resolved TTL and maxSize for named caches
- [ ] Add metrics test — assert `cache.gets` counters increment correctly with a real `MeterRegistry`
- [ ] Add graceful degradation integration test — pause the Redis container mid-test; assert application continues serving from L1
- [ ] Add SpotBugs / PMD and wire into `./gradlew check`
- [ ] Enforce JaCoCo minimum coverage gate (85% line coverage) in CI

**Exit criteria:** SpotBugs passes; JaCoCo reports ≥ 85%; all five missing tests are green.

---

### Phase 6 — Provider SPI

**Goal:** Make the library provider-agnostic.  
Implements the plan in [`EXTENSIBILITY.md`](EXTENSIBILITY.md).

| Sub-phase | Scope | Target version |
|-----------|-------|---------------|
| 6.1 — SPI refactor | Introduce `LocalCache`, `DistributedCache`, `InvalidationBroker` interfaces; refactor internals; zero behavior change | v0.2.0 |
| 6.2 — Guava + HashMap L1 | Two additional L1 providers + config namespace (`l1.provider`) | v0.3.0 |
| 6.3 — Single-tier mode | `l1.provider: none` and `l2.provider: none` via `NoOp` providers | v0.4.0 |
| 6.4 — Memcached L2 | Optional Memcached provider + broker decoupling | v0.5.0 |
| 6.5 — Custom bean registration | Full SPI Javadoc, threading/null/error contracts documented | v1.0.0 |

Each sub-phase is independently shippable and must leave all existing tests green.

---

### Phase 7 — Ecosystem and Growth

**Goal:** Easy to find, easy to adopt, easy to contribute to.

- [ ] **Example application** — `examples/product-service/` in the repo: a minimal Spring Boot app using `@Cacheable` with the starter, with a `docker-compose.yml` for Redis
- [ ] **Javadoc site** — Generate via `./gradlew javadoc` and publish to GitHub Pages on each release
- [ ] **Extended documentation** — Migration guide, performance tuning guide, troubleshooting FAQ (GitHub Wiki or mkdocs)
- [ ] **Announcement** — Blog post referencing the design rationale, post to Reddit r/java, Spring community forums, Hacker News (Show HN)

---

## Effort Summary

| Phase | Focus | Minimum viable release? | Estimated effort |
|-------|-------|------------------------|-----------------|
| 1 — Repo foundation | .gitignore, community docs, templates | Required before any push | 1 day |
| 2 — Bug fixes | SCAN, thundering herd, putIfAbsent, ObjectMapper | Required before v1.0 | 1–2 days |
| 3 — CI/CD | GitHub Actions, GPG, Sonatype | Required for Maven Central | 2–3 days |
| 4 — Multi-module | autoconfigure + starter + BOM | First public release | 2–3 days |
| 5 — Test coverage | Invalidation, degradation, SpotBugs, JaCoCo | Before v1.0 | 3–4 days |
| 6 — Provider SPI | Extensibility roadmap | v1.0 milestone | 2–4 weeks |
| 7 — Ecosystem | Examples, docs, announcement | Growth phase | Ongoing |

**Minimum viable public release:** Phases 1–4.  
**World-class open-source library:** Phases 1–6.

---

## Immediate Next Actions (Before First Push)

1. Fix `.gitignore` — this is the most urgent item. `.idea/` and `.claude/` will pollute the repo permanently once pushed.
2. Fix the README test command paths.
3. Add the Maven Central required POM fields.
4. Create `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`.
