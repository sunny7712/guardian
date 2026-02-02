package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.redis.testcontainers.RedisContainer;
import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.TokenBucketStore;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.impl.RedisLuaTokenBucketStore;
import com.sunny.guardian.utils.GuardianClock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@Testcontainers
class RedisLuaTokenBucketStoreTest {

    @Container
    @ServiceConnection
    private static final RedisContainer REDIS_CONTAINER =
            new RedisContainer(DockerImageName.parse("redis:7.0.12-alpine"));

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public GuardianClock fixedClock() {
            return () -> 100000L;
        }

        @Bean
        @Primary
        public TokenBucketStore redisLuaTokenBucketStore(RedisTemplate<String, String> redisTemplate,
                                                            GuardianClock clock) {
            return new RedisLuaTokenBucketStore(redisTemplate, clock);
        }

    }

    @Autowired
    private TokenBucketRateLimiter tokenBucketRateLimiter;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig;

    @BeforeEach
    void setup() {
        assert redisTemplate.getConnectionFactory() != null;
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        Map<String, Map<String, TokenBucketQuota>> plans = new HashMap<>();
        Map<String, TokenBucketQuota> quotaMap = new HashMap<>();
        quotaMap.put("default", new TokenBucketQuota(10, 1));
        plans.put("test_plan", quotaMap);
        tokenBucketRateLimiterConfig.setPlans(plans);
    }

    @Test
    void testRedisConcurrency_ThunderingHerd() throws InterruptedException {

        int threadCount = 50;
        int capacity = 10;

        CountDownLatch startGun;
        CountDownLatch doneSignal;
        AtomicInteger allowedCount;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            startGun = new CountDownLatch(1);
            doneSignal = new CountDownLatch(threadCount);
            allowedCount = new AtomicInteger(0);

            RateLimitRequest request = new RateLimitRequest("redis_user", "test_plan", "default");

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startGun.await();
                        if (tokenBucketRateLimiter.allow(request)) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneSignal.countDown();
                    }
                });
            }
        } finally {
            executor.shutdown();
        }

        startGun.countDown();
        doneSignal.await();

        Assertions.assertEquals(capacity, allowedCount.get(),
                "Redis Race Condition! Allowed " + allowedCount.get() + " but capacity is " + capacity);
    }

    @Test
    void testRedisDataStructureIsHash() {
        // 1. Make a request
        RateLimitRequest request = new RateLimitRequest("hash_check_user", "test_plan", "default");
        tokenBucketRateLimiter.allow(request);

        // 2. Get Key
        String key = "hash_check_user:test_plan:default";

        // 3. Assert Type
        String type = redisTemplate.type(key).code();
        Assertions.assertEquals("hash", type, "Data should be stored as a Redis Hash, not String");

        // 4. Assert Field existence
        Object tokens = redisTemplate.opsForHash().get(key, "t");
        Object refill = redisTemplate.opsForHash().get(key, "r");

        Assertions.assertNotNull(tokens, "Field 't' (tokens) should exist");
        Assertions.assertNotNull(refill, "Field 'r' (lastRefill) should exist");
    }


}
