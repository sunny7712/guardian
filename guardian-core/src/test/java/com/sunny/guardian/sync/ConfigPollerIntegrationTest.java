package com.sunny.guardian.sync;

import com.redis.testcontainers.RedisContainer;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.config.SlidingWindowRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.sync.SlidingWindowCounterConfigProvider;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.sync.TokenBucketConfigProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "guardian.token-bucket.enabled=true",
        "guardian.token-bucket.plans.baseline_plan.default.bucketCapacity=50",
        "guardian.token-bucket.plans.baseline_plan.default.refillRate=10",
        "guardian.sliding-window-counter.enabled=true",
        "guardian.sliding-window-counter.plans.baseline_plan.default.requestLimit=50",
        "guardian.sliding-window-counter.plans.baseline_plan.default.windowSizeInSeconds=60"
})
class ConfigPollerIntegrationTest {

    @Container
    @ServiceConnection
    private static final RedisContainer REDIS_CONTAINER =
            new RedisContainer(DockerImageName.parse("redis:7.0.12-alpine"));

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private GuardianConfigScheduler scheduler;

    @Autowired
    private TokenBucketConfigProvider tokenBucketProvider;

    @MockitoSpyBean
    private SlidingWindowCounterConfigProvider slidingWindowProvider;

    @Autowired
    private MeterRegistry meterRegistry;

    @TestConfiguration
    static class MetricsConfig {
        @Bean
        @Primary
        public MeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @BeforeEach
    void setup() {
        assert redisTemplate.getConnectionFactory() != null;
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private void assertCounterIncrement(String providerName, String status, Runnable action) {
        double before = meterRegistry.counter("guardian.config.reload",
                "provider", providerName, "status", status).count();

        action.run();

        double after = meterRegistry.counter("guardian.config.reload",
                "provider", providerName, "status", status).count();

        Assertions.assertEquals(before + 1.0, after,
                String.format("Expected metric for %s (%s) to increment by exactly 1.", providerName, status));
    }

    @Test
    void testHappyPath_ValidConfigUpdatesMemoryAndMetrics() {
        // 1. Setup: Insert valid JSON into Redis
        String validJson = "{\"default\":{\"bucketCapacity\":999,\"refillRate\":5}}";
        redisTemplate.opsForHash().put("guardian:config:token-bucket", "new_dynamic_plan", validJson);

        // 2. Execute and assert counters
        assertCounterIncrement("TokenBucketConfigProvider", "success", () -> scheduler.poll());

        // 3. Assert Memory State: The AtomicReference swapped successfully
        TokenBucketRateLimiterConfig activeConfig = tokenBucketProvider.getConfig();
        Assertions.assertNotNull(activeConfig.getPlans().get("new_dynamic_plan"));
        Assertions.assertEquals(999, activeConfig.getPlans().get("new_dynamic_plan").get("default").getBucketCapacity());
        Assertions.assertEquals(5, activeConfig.getPlans().get("new_dynamic_plan").get("default").getRefillRate());
    }

    @Test
    void testSlidingWindowDynamicConfigUpdates() {
        // 1. Setup: Insert valid JSON into Redis for sliding window
        String validJson = "{\"default\":{\"requestLimit\":100,\"windowSizeInSeconds\":120}}";
        redisTemplate.opsForHash().put("guardian:config:sliding-window-counter", "dynamic_window_plan", validJson);

        // 2. Execute and assert counters
        assertCounterIncrement("SlidingWindowCounterConfigProvider", "success", () -> scheduler.poll());

        // 3. Assert Memory State
        SlidingWindowRateLimiterConfig activeConfig = slidingWindowProvider.getConfig();
        Assertions.assertNotNull(activeConfig.getPlans().get("dynamic_window_plan"));
        Assertions.assertEquals(100, activeConfig.getPlans().get("dynamic_window_plan").get("default").getRequestLimit());
        Assertions.assertEquals(120, activeConfig.getPlans().get("dynamic_window_plan").get("default").getWindowSizeInSeconds());
    }

    @Test
    void testGhostState_EmptyRedisRetainsYamlBaseline() {
        // 1. Setup: Redis is completely empty

        // 2. Execute
        scheduler.poll();

        // 3. Assert: We did not overwrite the memory with nulls. We retained the YAML baseline.
        TokenBucketRateLimiterConfig activeConfig = tokenBucketProvider.getConfig();
        Assertions.assertNotNull(activeConfig.getPlans().get("baseline_plan"));
        Assertions.assertEquals(50, activeConfig.getPlans().get("baseline_plan").get("default").getBucketCapacity());
    }

    @Test
    void testPoisonPill_MalformedJsonRejectsUpdateAndRecordsError() {
        // 1. Setup: Admin makes a typo (String instead of integer for capacity)
        String poisonJson = "{\"default\":{\"bucketCapacity\":\"GARBAGE_DATA\",\"refillRate\":5}}";
        redisTemplate.opsForHash().put("guardian:config:token-bucket", "broken_plan", poisonJson);

        // 2. Execute and assert counters
        assertCounterIncrement("TokenBucketConfigProvider", "error", () -> scheduler.poll());

        // 3. Assert Memory: Update aborted, retained YAML baseline
        TokenBucketRateLimiterConfig activeConfig = tokenBucketProvider.getConfig();
        Assertions.assertNull(activeConfig.getPlans().get("broken_plan"));
        Assertions.assertNotNull(activeConfig.getPlans().get("baseline_plan"));
        Assertions.assertEquals(50, activeConfig.getPlans().get("baseline_plan").get("default").getBucketCapacity());
        Assertions.assertEquals(10, activeConfig.getPlans().get("baseline_plan").get("default").getRefillRate());
    }

    @Test
    void testPartialDegradation_OneFailureDoesNotCrashTheLoop() throws Exception {
        // 1. Setup: Token Bucket gets valid JSON
        String validJson = "{\"default\":{\"bucketCapacity\":100,\"refillRate\":10}}";
        redisTemplate.opsForHash().put("guardian:config:token-bucket", "valid_plan", validJson);

        // 2. Setup: Force Sliding Window Provider to throw a catastrophic network exception
        Mockito.doThrow(new RuntimeException("Simulated Redis Timeout"))
                .when(slidingWindowProvider).reload();

        double initialTimestamp = meterRegistry.get("guardian.config.last_updated_timestamp").gauge().value();

        // 3. Execute and assert counters
        assertCounterIncrement("TokenBucketConfigProvider", "success",
                () -> assertCounterIncrement("SlidingWindowCounterConfigProvider", "error",
                        () -> scheduler.poll()));

        // 4. Assert Blast Radius Isolation
        // Token Bucket succeeded despite the other failure
        Assertions.assertNotNull(tokenBucketProvider.getConfig().getPlans().get("valid_plan"));


        // 5. Assert Global Gauge (Should NOT update because allSuccess = false)
        double timestamp = meterRegistry.get("guardian.config.last_updated_timestamp").gauge().value();
        Assertions.assertEquals(initialTimestamp, timestamp, "Timestamp should not update on partial failure");
    }

}
