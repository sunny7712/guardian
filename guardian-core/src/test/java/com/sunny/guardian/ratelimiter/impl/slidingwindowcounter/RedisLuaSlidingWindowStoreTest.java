package com.sunny.guardian.ratelimiter.impl.slidingwindowcounter;

import com.redis.testcontainers.RedisContainer;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.dto.SlidingWindowQuota;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.storage.impl.RedisLuaSlidingWindowStore;
import com.sunny.guardian.utils.GuardianClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@Testcontainers
public class RedisLuaSlidingWindowStoreTest {

    @Container
    @ServiceConnection
    private static final RedisContainer REDIS_CONTAINER =
            new RedisContainer(DockerImageName.parse("redis:7.0.12-alpine"));

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        public MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        public RedisLuaSlidingWindowStore store(RedisTemplate<String, String> redisTemplate, GuardianClock clock) {
            return new RedisLuaSlidingWindowStore(redisTemplate, clock);
        }

        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private RedisLuaSlidingWindowStore store;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private MutableClock mutableClock;

    @BeforeEach
    void setup() {
        assert redisTemplate.getConnectionFactory() != null;
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        mutableClock.setTime(60000L);
    }

    @Test
    void testConcurrency_ThunderingHerd() throws InterruptedException {
        int threadCount = 50;
        int capacity = 10;
        SlidingWindowQuota quota = new SlidingWindowQuota(capacity, 60);

        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGun.await();
                    if (store.allowRequest("concurrent_user", quota, 1)) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        startGun.countDown();
        doneSignal.await();
        executor.shutdown();

        Assertions.assertEquals(capacity, allowedCount.get(),
                "Lua script failed to guarantee atomicity under concurrent load.");
    }

    @Test
    void testPartialWindowMath_TheSlidingEffect() {
        // Limit: 10 requests per 60 seconds
        SlidingWindowQuota quota = new SlidingWindowQuota(10, 60);
        String key = "math_user";

        // TIME: 60,000ms (Start of Window 1)
        // 1. Consume exactly 10 requests. Limit reached.
        for (int i = 0; i < 10; i++) {
            Assertions.assertTrue(store.allowRequest(key, quota, 1));
        }
        Assertions.assertFalse(store.allowRequest(key, quota, 1), "Should be blocked after 10 requests");

        // 2. Advance time to 150,000ms.
        // This puts us in Window 2, exactly 50% into the window (30 seconds into a 60 second window).
        // The algorithm calculates: (Previous Window Count * 0.5) + (Current Window Count)
        // Expected: (10 * 0.5) + 0 = 5 estimated used capacity.
        // Therefore, we should have exactly 5 capacity left.
        mutableClock.setTime(150000L);

        // 3. We should be able to make exactly 5 more requests
        for (int i = 0; i < 5; i++) {
            Assertions.assertTrue(store.allowRequest(key, quota, 1), "Request " + (i+1) + " should be allowed by partial window");
        }

        // 4. The 6th request should fail
        Assertions.assertFalse(store.allowRequest(key, quota, 1), "Should be blocked, partial window capacity exhausted");
    }



    static class MutableClock implements GuardianClock {
        private long currentTime = 60000L;

        @Override
        public long currentTimeMillis() {
            return currentTime;
        }

        public void setTime(long time) {
            this.currentTime = time;
        }
    }
}
