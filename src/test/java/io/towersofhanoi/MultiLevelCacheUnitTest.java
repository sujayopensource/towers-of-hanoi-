package io.towersofhanoi;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.towersofhanoi.core.MultiLevelCache;
import io.towersofhanoi.core.NullValueMarker;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiLevelCacheUnitTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;

    private com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeine;
    private MultiLevelCache cache;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        caffeine = Caffeine.newBuilder().maximumSize(100).build();
        cache = new MultiLevelCache("test", caffeine, redisTemplate, Duration.ofMinutes(10), false, null);
    }

    @Test
    void get_returnsNullOnMiss() {
        when(valueOps.get("test:missing")).thenReturn(null);
        assertThat(cache.get("missing")).isNull();
    }

    @Test
    void get_hitsL1WithoutTouchingRedis() {
        caffeine.put("k1", "v1");
        Cache.ValueWrapper result = cache.get("k1");
        assertThat(result).isNotNull();
        assertThat(result.get()).isEqualTo("v1");
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void get_hitsL2AndBackfillsL1() {
        when(valueOps.get("test:k2")).thenReturn("v2");
        Cache.ValueWrapper result = cache.get("k2");
        assertThat(result.get()).isEqualTo("v2");
        assertThat(caffeine.getIfPresent("k2")).isEqualTo("v2");
    }

    @Test
    void put_writesBothLayers() {
        cache.put("k3", "v3");
        assertThat(caffeine.getIfPresent("k3")).isEqualTo("v3");
        verify(valueOps).set(eq("test:k3"), eq("v3"), any(Duration.class));
    }

    @Test
    void evict_removesBothLayers() {
        caffeine.put("k4", "v4");
        cache.evict("k4");
        assertThat(caffeine.getIfPresent("k4")).isNull();
        verify(redisTemplate).delete("test:k4");
    }

    @Test
    void get_withValueLoader_callsLoaderOnMiss() {
        when(valueOps.get("test:k5")).thenReturn(null);
        AtomicInteger calls = new AtomicInteger();
        String result = cache.get("k5", () -> {
            calls.incrementAndGet();
            return "loaded";
        });
        assertThat(result).isEqualTo("loaded");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void get_withValueLoader_doesNotCallLoaderOnL1Hit() {
        caffeine.put("k6", "cached");
        AtomicInteger calls = new AtomicInteger();
        String result = cache.get("k6", () -> {
            calls.incrementAndGet();
            return "loaded";
        });
        assertThat(result).isEqualTo("cached");
        assertThat(calls.get()).isZero();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void put_skipsNullWhenCacheNullValuesIsFalse() {
        cache.put("k7", null);
        assertThat(caffeine.getIfPresent("k7")).isNull();
        verifyNoInteractions(valueOps);
    }

    @Test
    void put_storesNullMarkerWhenCacheNullValuesIsTrue() {
        MultiLevelCache nullAllowed = new MultiLevelCache(
            "nulltest", caffeine, redisTemplate, Duration.ofMinutes(10), true, null);
        nullAllowed.put("k8", null);
        assertThat(caffeine.getIfPresent("k8")).isInstanceOf(NullValueMarker.class);
        verify(valueOps).set(eq("nulltest:k8"), any(NullValueMarker.class), any(Duration.class));
    }

    @Test
    void evictLocalOnly_doesNotTouchRedis() {
        caffeine.put("k9", "v9");
        cache.evictLocalOnly("k9");
        assertThat(caffeine.getIfPresent("k9")).isNull();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void redisFault_doesNotPropagateToCallerOnGet() {
        when(valueOps.get(any())).thenThrow(new RuntimeException("Redis unavailable"));
        assertThat(cache.get("k10")).isNull();
    }

    @Test
    void redisFault_doesNotPropagateToCallerOnPut() {
        doThrow(new RuntimeException("Redis unavailable"))
            .when(valueOps).set(any(), any(), any(Duration.class));
        // Should not throw; local cache still populated
        cache.put("k11", "v11");
        assertThat(caffeine.getIfPresent("k11")).isEqualTo("v11");
    }
}
