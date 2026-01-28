package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.TokenBucketStore;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.impl.InMemoryTokenBucketStore;

import com.sunny.guardian.utils.GuardianClock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class InMemoryTokenBucketRateLimiterTest {

    @Test
    void testConcurrency_ThunderingHerd() throws InterruptedException {

        // 1. Setup Config
        TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig = new TokenBucketRateLimiterConfig();
        Map<String, Map<String, TokenBucketQuota>> plans = new HashMap<>();
        Map<String, TokenBucketQuota> quotaMap = new HashMap<>();

        // Capacity: 10, Refill: 10 Tokens / sec
        quotaMap.put("default", new TokenBucketQuota(10, 1));
        plans.put("test_plan", quotaMap);
        tokenBucketRateLimiterConfig.setPlans(plans);

        // 2. Setup Dependencies
        GuardianClock fixedClock = () -> 100000L;

        // Inject Clock into the Store
        TokenBucketStore store = new InMemoryTokenBucketStore(fixedClock);

        // Inject Store into the Limiter
        TokenBucketRateLimiter tokenBucketRateLimiter = new TokenBucketRateLimiter(
                store,
                tokenBucketRateLimiterConfig
        );

        RateLimitRequest rateLimitRequest = new RateLimitRequest("user_1", "test_plan", "default");

        // 3. Execute
        int threadCount = 300;
        AtomicInteger allowedCount;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch endSignal = new CountDownLatch(threadCount);
            allowedCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    try {
                        startSignal.await();
                        if (tokenBucketRateLimiter.allow(rateLimitRequest)) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endSignal.countDown();
                    }
                });
            }

            startSignal.countDown();
            endSignal.await();
        } finally {
            executorService.shutdown();
        }

        // 4. Assertion
        Assertions.assertEquals(10, allowedCount.get(), "Race condition detected! More requests allowed than capacity.");
    }

    @Test
    void testRefill_TimeTravel() {
        // 1. Setup
        TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig = new TokenBucketRateLimiterConfig();
        Map<String, Map<String, TokenBucketQuota>> plans = new HashMap<>();
        Map<String, TokenBucketQuota> quotaMap = new HashMap<>();

        // Capacity: 10, Refill: 1 token/sec
        quotaMap.put("default", new TokenBucketQuota(10, 1));
        plans.put("test_plan", quotaMap);
        tokenBucketRateLimiterConfig.setPlans(plans);

        // Mutable clock
        AtomicLong time = new AtomicLong(100000L);
        GuardianClock mockClock = time::get;

        // Inject Clock into Store
        TokenBucketStore store = new InMemoryTokenBucketStore(mockClock);

        TokenBucketRateLimiter tokenBucket = new TokenBucketRateLimiter(
                store,
                tokenBucketRateLimiterConfig
        );

        RateLimitRequest rateLimitRequest = new RateLimitRequest("user_1", "test_plan", "default");

        // 2. Execute
        // We expect 10 successes
        for(int i = 0; i < 10; i++) {
            Assertions.assertTrue(tokenBucket.allow(rateLimitRequest), "Request " + i + " should be allowed");
        }
        Assertions.assertFalse(tokenBucket.allow(rateLimitRequest), "Bucket should be empty now");

        // Time travel: Advance clock by 5000ms (5 seconds) -> 5 tokens refilled
        time.addAndGet(5000L);

        for(int i = 0; i < 5; i++) {
            Assertions.assertTrue(tokenBucket.allow(rateLimitRequest), "Refilled Request " + i + " should be allowed");
        }
        Assertions.assertFalse(tokenBucket.allow(rateLimitRequest), "Bucket should be empty again");
    }
}