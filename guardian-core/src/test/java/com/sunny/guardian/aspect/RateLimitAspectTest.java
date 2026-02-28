package com.sunny.guardian.aspect;


import com.sunny.guardian.annotation.GuardianRateLimit;
import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.exception.RateLimitExceededException;
import com.sunny.guardian.ratelimiter.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@SpringBootTest
class RateLimitAspectTest {

    @Autowired
    private TestService testService;

    @MockitoBean(name = "tokenBucketRateLimiter")
    private RateLimiter tokenBucketRateLimiter;

    @MockitoBean(name = "customRateLimiter")
    private RateLimiter customLimiter;



    @Test
    void testAspectInterceptsAndAllowsRequest() {
        // Arrange: RateLimiter says "Yes"
        when(tokenBucketRateLimiter.allow(any(RateLimitRequest.class))).thenReturn(true);

        // Act
        String result = testService.sensitiveOperation("user_123");

        // Assert
        Assertions.assertEquals("success", result);

        // Verify the Aspect called the RateLimiter with the correct SpEL-resolved key
        verify(tokenBucketRateLimiter).allow(argThat(request ->
                request.key().equals("user_123") &&
                        request.plan().equals("pro_plan") &&
                        request.quota().equals("read_limit")
        ));
    }

    @Test
    void testAspectInterceptsAndBlocksRequest() {
        // Arrange: RateLimiter says "No"
        when(tokenBucketRateLimiter.allow(any(RateLimitRequest.class))).thenReturn(false);

        // Act & Assert
        Assertions.assertThrows(RateLimitExceededException.class, () -> {
            testService.sensitiveOperation("user_blocked");
        });

        // Verify logic was executed
        verify(tokenBucketRateLimiter).allow(any(RateLimitRequest.class));
    }

    @Test
    void testMissingKeyThrowsException() {
        // Act & Assert: Should throw IllegalArgumentException
        // because the annotation on 'brokenOperation' has an empty key
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            testService.brokenOperation("user_X");
        });

        // RateLimiter should NEVER be called if validation fails
        verifyNoInteractions(tokenBucketRateLimiter);
    }

    @Test
    void testObjectNavigationSpEL() {
        when(tokenBucketRateLimiter.allow(any())).thenReturn(true);

        User user = new User("alice", "gold");
        testService.complexOperation(user);

        verify(tokenBucketRateLimiter).allow(argThat(req ->
                req.key().equals("alice") && req.plan().equals("gold")
        ));
    }

    @Test
    void testDefaultAlgorithmRouting() {
        when(tokenBucketRateLimiter.allow(any(RateLimitRequest.class))).thenReturn(true);

        String result = testService.sensitiveOperation("user_123");

        Assertions.assertEquals("success", result);

        // Verify it routed to the DEFAULT bucket
        verify(tokenBucketRateLimiter).allow(argThat(request ->
                request.key().equals("user_123") &&
                        request.plan().equals("pro_plan")
        ));
        // Verify the custom limiter was untouched
        verifyNoInteractions(customLimiter);
    }

    @Test
    void testCustomAlgorithmRouting() {
        when(customLimiter.allow(any(RateLimitRequest.class))).thenReturn(true);

        String result = testService.customOperation("user_456");

        Assertions.assertEquals("success_custom", result);

        // Verify it routed to the CUSTOM bucket
        verify(customLimiter).allow(argThat(request -> request.key().equals("user_456")));
        verifyNoInteractions(tokenBucketRateLimiter);
    }

    @Test
    void testUnknownAlgorithmThrowsException() {
        // Act & Assert
        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> {
            testService.unknownAlgorithmOperation("user_789");
        });

        Assertions.assertTrue(exception.getMessage().contains("No algorithm bean found"));
        verifyNoInteractions(tokenBucketRateLimiter, customLimiter);
    }



    @TestConfiguration
    @EnableAspectJAutoProxy // Helper to enable AOP in this test slice
    static class Config {

        @Bean
        public RateLimitAspect rateLimitAspect(Map<String, RateLimiter> rateLimiters) {
            return new RateLimitAspect(rateLimiters);
        }

        @Bean
        public TestService testService() {
            return new TestService();
        }

        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

    }

    @Service
    static class TestService {

        @GuardianRateLimit(key = "#userId", plan = "pro_plan", quota = "read_limit")
        public String sensitiveOperation(String userId) {
            return "success";
        }

        @GuardianRateLimit(algorithm = "customRateLimiter", key = "#userId", plan = "basic", quota = "default")
        public String customOperation(String userId) {
            return "success_custom";
        }

        @GuardianRateLimit(algorithm = "typoLimiter", key = "#userId")
        public String unknownAlgorithmOperation(String userId) {
            return "should_fail";
        }

        // Intentionally broken configuration (empty key)
        @GuardianRateLimit(key = "", plan = "default", quota = "default")
        public String brokenOperation(String userId) {
            return "should_fail";
        }

        @GuardianRateLimit(key = "#user.name", plan = "#user.tier", quota = "default")
        public void complexOperation(User user) {
            // no-op
        }
    }

    record User(String name, String tier) {}
}
