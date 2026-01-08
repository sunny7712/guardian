package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.redis.testcontainers.RedisContainer;
import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.utils.GuardianClock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class RedisTransactionStorageTokenBucketRateLimiterTest {

    @Container
    private static final RedisContainer REDIS_CONTAINER =
            new RedisContainer(DockerImageName.parse("redis:7.0.12-alpine"));

    @DynamicPropertySource
    private static void registeredRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.redis.port", REDIS_CONTAINER::getFirstMappedPort);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public GuardianClock fixedClock() {
            return () -> 100000L;
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
        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
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
        }

        startGun.countDown();
        doneSignal.await();

        Assertions.assertEquals(capacity, allowedCount.get(),
                "Redis Race Condition! Allowed " + allowedCount.get() + " but capacity is " + capacity);
    }


}
