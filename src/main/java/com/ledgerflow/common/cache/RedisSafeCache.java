package com.ledgerflow.common.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis access that can NEVER take the application down. Redis here is a
 * projection layer (cached pages, rate-limit counters); PostgreSQL remains
 * the source of truth, so every Redis failure degrades to "no cache" or
 * "no limit" instead of an error.
 *
 * A small breaker avoids paying a connection timeout on every request
 * while Redis is down: after a failure, Redis is considered down for 10
 * seconds and all operations no-op instantly.
 *
 * TTLs get 10 percent jitter so a popular key's expiry does not stampede
 * the database with simultaneous refills.
 */
@Component
public class RedisSafeCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSafeCache.class);
    private static final long BREAKER_OPEN_MS = 10_000;

    private final StringRedisTemplate redis;
    private final AtomicLong downUntil = new AtomicLong(0);
    private final io.micrometer.core.instrument.Counter hits;
    private final io.micrometer.core.instrument.Counter misses;

    public RedisSafeCache(StringRedisTemplate redis,
                          io.micrometer.core.instrument.MeterRegistry registry) {
        this.redis = redis;
        this.hits = registry.counter("ledgerflow.cache.gets", "result", "hit");
        this.misses = registry.counter("ledgerflow.cache.gets", "result", "miss");
    }

    /** @return cached value, or null on miss OR any Redis problem. */
    public String get(String key) {
        if (isDown()) {
            misses.increment();
            return null;
        }
        try {
            String value = redis.opsForValue().get(key);
            (value != null ? hits : misses).increment();
            return value;
        } catch (RuntimeException e) {
            tripBreaker(e);
            misses.increment();
            return null;
        }
    }

    public void put(String key, String value, Duration ttl) {
        if (isDown()) {
            return;
        }
        try {
            long jitterMs = ThreadLocalRandom.current().nextLong(ttl.toMillis() / 10 + 1);
            redis.opsForValue().set(key, value, ttl.plusMillis(jitterMs));
        } catch (RuntimeException e) {
            tripBreaker(e);
        }
    }

    public void evict(String... keys) {
        if (isDown() || keys.length == 0) {
            return;
        }
        try {
            redis.delete(java.util.List.of(keys));
        } catch (RuntimeException e) {
            tripBreaker(e);
        }
    }

    /**
     * Fixed-window counter for rate limiting. @return the count within the
     * current window, or -1 when Redis is unavailable (callers fail OPEN:
     * an attacker taking Redis down must not turn into a full API outage,
     * and correctness never depended on the limiter).
     */
    public long incrementWindow(String key, Duration window) {
        if (isDown()) {
            return -1;
        }
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, window);
            }
            return count == null ? -1 : count;
        } catch (RuntimeException e) {
            tripBreaker(e);
            return -1;
        }
    }

    public boolean isDown() {
        return System.currentTimeMillis() < downUntil.get();
    }

    private void tripBreaker(RuntimeException e) {
        long until = System.currentTimeMillis() + BREAKER_OPEN_MS;
        if (downUntil.getAndSet(until) < System.currentTimeMillis()) {
            log.warn("Redis unavailable, failing open for {}ms: {}", BREAKER_OPEN_MS, e.getMessage());
        }
    }
}
