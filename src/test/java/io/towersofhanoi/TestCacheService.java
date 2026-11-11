package io.towersofhanoi;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TestCacheService {

    private final AtomicInteger loadCount = new AtomicInteger();

    @Cacheable(value = "items", key = "#id")
    public String findById(String id) {
        loadCount.incrementAndGet();
        return "value-" + id;
    }

    @CachePut(value = "items", key = "#id")
    public String update(String id, String value) {
        return value;
    }

    @CacheEvict(value = "items", key = "#id")
    public void evict(String id) {}

    @CacheEvict(value = "items", allEntries = true)
    public void evictAll() {}

    public int getLoadCount() {
        return loadCount.get();
    }

    public void resetLoadCount() {
        loadCount.set(0);
    }
}
