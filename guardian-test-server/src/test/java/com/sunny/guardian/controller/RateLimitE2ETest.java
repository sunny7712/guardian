package com.sunny.guardian.controller;

import com.redis.testcontainers.RedisContainer;
import com.sunny.guardian.utils.GuardianClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "guardian.token-bucket.plans.load_test_plan.default.bucketCapacity=100",
        "guardian.token-bucket.plans.load_test_plan.default.refillRate=50",
        "guardian.sliding-window.plans.strict_plan.default.requestLimit=5",
        "guardian.sliding-window.plans.strict_plan.default.windowSizeInSeconds=60"
})
class RateLimitE2ETest {

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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private MutableClock mutableClock;


    @BeforeEach
    void setup() {
        // Flush Redis between tests to ensure a clean slate
        assert redisTemplate.getConnectionFactory() != null;
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        mutableClock.setTime(1000000L);
    }

    @Test
    void testTokenBucketAnnotation_EnforcesLimitAndReturns429() throws Exception {
        String url = "/limit-token-bucket?user=e2e_user_1";

        // 1. Consume the entire bucket
        // Because the MutableClock is frozen at 1000000L, exactly 0 tokens will refill during this loop.
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(MockMvcRequestBuilders.get(url))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ALLOWED"));
        }

        // 2. The 101st request MUST be blocked with a 429
        mockMvc.perform(MockMvcRequestBuilders.get(url))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string("BLOCKED: Rate limit exceeded for key: e2e_user_1"));
    }

    @Test
    void testTokenBucket_RefillsOverTime() throws Exception {
        String url = "/limit-token-bucket?user=time_travel_user";

        // Exhaust the bucket (100 tokens)
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isOk());
        }

        // Assert blocked
        mockMvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isTooManyRequests());

        // Fast-forward time by exactly 1000 milliseconds (1 second)
        // Refill rate is 50/sec. So we should exactly get 50 tokens back.
        mutableClock.advance(1000L);

        // Consume the newly refilled 50 tokens
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isOk());
        }

        // 51st request should be blocked again
        mockMvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isTooManyRequests());
    }

    @Test
    void testSlidingWindowAnnotation_EnforcesLimitAndReturns429() throws Exception {
        String url = "/limit-sliding-window-counter?user=e2e_user_2";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isOk());
        }
        mockMvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isTooManyRequests());
    }

    @Test
    void testIsolationBetweenUsers() throws Exception {
        String urlUserA = "/limit-sliding-window-counter?user=user_A";
        String urlUserB = "/limit-sliding-window-counter?user=user_B";

        for (int i = 0; i < 5; i++) { mockMvc.perform(MockMvcRequestBuilders.get(urlUserA)); }
        mockMvc.perform(MockMvcRequestBuilders.get(urlUserA)).andExpect(status().isTooManyRequests());

        mockMvc.perform(MockMvcRequestBuilders.get(urlUserB)).andExpect(status().isOk());
    }

    @Test
    void testE2EConcurrency_NoRaceConditionsAtTheHttpLayer() throws Exception {
        String url = "/limit-sliding-window-counter?user=e2e_concurrent_user";
        int threadCount = 50;
        int expectedAllowed = 5;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    int status = mockMvc.perform(MockMvcRequestBuilders.get(url)).andReturn().getResponse().getStatus();
                    if (status == 200) allowedCount.incrementAndGet();
                    if (status == 429) blockedCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        done.await();
        executor.shutdown();

        assert allowedCount.get() == expectedAllowed : "Expected " + expectedAllowed + " allowed, got " + allowedCount.get();
        assert blockedCount.get() == (threadCount - expectedAllowed) : "Expected " + (threadCount - expectedAllowed) + " blocked, got " + blockedCount.get();
    }




    static class MutableClock implements GuardianClock {
        private long currentTime = 1000000L;

        @Override
        public long currentTimeMillis() {
            return currentTime;
        }

        public void setTime(long time) {
            this.currentTime = time;
        }

        public void advance(long ms) {
            this.currentTime += ms;
        }
    }
}
