package com.sunny.guardian.sync;

import com.redis.testcontainers.RedisContainer;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.sync.TokenBucketConfigProvider;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "guardian.token-bucket.enabled=true",
        "guardian.token-bucket.plans.baseline_plan.default.bucketCapacity=50",
        "guardian.token-bucket.plans.baseline_plan.default.refillRate=10"
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

    @Autowired
    private TokenBucketRateLimiterConfig yamlConfig;


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
        meterRegistry.clear();
    }

    @Test
    void testHappyPath_ValidConfigUpdatesMemoryAndMetrics() {
        // 1. Setup: Insert valid JSON into Redis
        String validJson = "{\"default\":{\"bucketCapacity\":999,\"refillRate\":5}}";
        redisTemplate.opsForHash().put("guardian:config:token-bucket", "new_dynamic_plan", validJson);

        // 2. Execute: Manually trigger the poller
        scheduler.poll();

        // 3. Assert Memory State: The AtomicReference swapped successfully
        TokenBucketRateLimiterConfig activeConfig = tokenBucketProvider.getConfig();
        Assertions.assertNotNull(activeConfig.getPlans().get("new_dynamic_plan"));
        Assertions.assertEquals(999, activeConfig.getPlans().get("new_dynamic_plan").get("default").getBucketCapacity());

        // 4. Assert Observability: Metrics recorded correctly
        double successCount = meterRegistry.counter("guardian.config.reload",
                "provider", "TokenBucketConfigProvider",
                "status", "success").count();
        Assertions.assertEquals(1.0, successCount);
    }

}
