package io.towersofhanoi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.towersofhanoi.core.MultiLevelCache;
import io.towersofhanoi.core.MultiLevelCacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@Testcontainers
class MultiLevelCacheIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired CacheManager cacheManager;
    @Autowired TestCacheService service;

    @BeforeEach
    void setUp() {
        service.evictAll();
        service.resetLoadCount();
    }

    @Test
    void cacheManagerIsMultiLevel() {
        assertThat(cacheManager).isInstanceOf(MultiLevelCacheManager.class);
    }

    @Test
    void firstCallLoadsFromSource() {
        String result = service.findById("1");
        assertThat(result).isEqualTo("value-1");
        assertThat(service.getLoadCount()).isEqualTo(1);
    }

    @Test
    void subsequentCallsReturnFromL1WithoutHittingSource() {
        service.findById("2");
        service.findById("2");
        service.findById("2");
        assertThat(service.getLoadCount()).isEqualTo(1);
    }

    @Test
    void evictRemovesFromBothLayers() {
        service.findById("3");
        service.evict("3");
        service.findById("3");
        assertThat(service.getLoadCount()).isEqualTo(2);
    }

    @Test
    void updateWritesThroughBothLayers() {
        service.findById("4");
        service.update("4", "updated-4");
        // L1 should now have updated value without going to source
        String result = service.findById("4");
        assertThat(result).isEqualTo("updated-4");
        assertThat(service.getLoadCount()).isEqualTo(1);
    }

    @Test
    void l1MissButL2HitBackfillsL1() {
        service.findById("5");
        // Evict from L1 only — simulates local cache expiry while Redis still has the value
        MultiLevelCache mlc = (MultiLevelCache) cacheManager.getCache("items");
        assertThat(mlc).isNotNull();
        mlc.evictLocalOnly("5");

        // Next call hits L2, back-fills L1, does NOT call the source
        service.findById("5");
        assertThat(service.getLoadCount()).isEqualTo(1);
    }

    @Test
    void serializedObjectRoundTrips() {
        String val = service.findById("roundtrip");
        MultiLevelCache mlc = (MultiLevelCache) cacheManager.getCache("items");
        assertThat(mlc).isNotNull();
        mlc.evictLocalOnly("roundtrip");

        // Value comes from Redis, deserialized via Jackson
        String fromRedis = service.findById("roundtrip");
        assertThat(fromRedis).isEqualTo(val);
        assertThat(service.getLoadCount()).isEqualTo(1);
    }
}
