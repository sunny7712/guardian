package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.storage.impl.InMemoryStorage;
import com.sunny.guardian.utils.GuardianClock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

class TokenBucketRateLimiterTest {

    @Test
    void testConcurrency_ThunderingHerd() throws InterruptedException {

        // 1. Setup
        TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig = new TokenBucketRateLimiterConfig();
        Map<String, Map<String, TokenBucketQuota>> plans = new HashMap<>();
        Map<String, TokenBucketQuota> quotaMap = new HashMap<>();

        // Capacity: 10, Refill: 10 Tokens / sec
        quotaMap.put("default", new TokenBucketQuota(10, 1));
        plans.put("test_plan", quotaMap);
        tokenBucketRateLimiterConfig.setPlans(plans);

        // Frozen clock for testing
        GuardianClock fixedClock = () -> 100000L;

        TokenBucketRateLimiter tokenBucketRateLimiter = new TokenBucketRateLimiter(
                new InMemoryStorage<>(),
                tokenBucketRateLimiterConfig,
                fixedClock
        );

        RateLimitRequest rateLimitRequest = new RateLimitRequest("user_1", "test_plan", "default");

        // 2. Execute
        int threadCount = 300;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch endSignal = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startSignal.await();
                    if(tokenBucketRateLimiter.allow(rateLimitRequest)) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finally {
                    endSignal.countDown();
                }
            });
        }

        startSignal.countDown();
        endSignal.await();
        executorService.shutdown();

        // 3. Assertion
        Assertions.assertEquals(10, allowedCount.get(), "Race condition detected! More requests allowed than capacity.");
    }
}
