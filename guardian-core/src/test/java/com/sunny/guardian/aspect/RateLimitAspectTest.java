package com.sunny.guardian.aspect;


import com.sunny.guardian.annotation.GuardianRateLimit;
import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.exception.RateLimitExceededException;
import com.sunny.guardian.ratelimiter.RateLimiter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@SpringBootTest
public class RateLimitAspectTest {

    @Autowired
    private TestService testService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @Test
    void testAspectInterceptsAndAllowsRequest() {
        // Arrange: RateLimiter says "Yes"
        when(rateLimiter.allow(any(RateLimitRequest.class))).thenReturn(true);

        // Act
        String result = testService.sensitiveOperation("user_123");

        // Assert
        Assertions.assertEquals("success", result);

        // Verify the Aspect called the RateLimiter with the correct SpEL-resolved key
        verify(rateLimiter).allow(argThat(request ->
                request.key().equals("user_123") &&
                        request.plan().equals("pro_plan") &&
                        request.quota().equals("read_limit")
        ));
    }

    @Test
    void testAspectInterceptsAndBlocksRequest() {
        // Arrange: RateLimiter says "No"
        when(rateLimiter.allow(any(RateLimitRequest.class))).thenReturn(false);

        // Act & Assert
        Assertions.assertThrows(RateLimitExceededException.class, () -> {
            testService.sensitiveOperation("user_blocked");
        });

        // Verify logic was executed
        verify(rateLimiter).allow(any(RateLimitRequest.class));
    }

    @Test
    void testMissingKeyThrowsException() {
        // Act & Assert: Should throw IllegalArgumentException
        // because the annotation on 'brokenOperation' has an empty key
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            testService.brokenOperation("user_X");
        });

        // RateLimiter should NEVER be called if validation fails
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void testObjectNavigationSpEL() {
        when(rateLimiter.allow(any())).thenReturn(true);

        User user = new User("alice", "gold");
        testService.complexOperation(user);

        verify(rateLimiter).allow(argThat(req ->
                req.key().equals("alice") && req.plan().equals("gold")
        ));
    }



    @TestConfiguration
    @EnableAspectJAutoProxy // Helper to enable AOP in this test slice
    static class Config {

        @Bean
        public RateLimitAspect rateLimitAspect(RateLimiter rateLimiter) {
            return new RateLimitAspect(rateLimiter);
        }

        @Bean
        public TestService testService() {
            return new TestService();
        }
    }

    @Service
    static class TestService {

        @GuardianRateLimit(key = "#userId", plan = "pro_plan", quota = "read_limit")
        public String sensitiveOperation(String userId) {
            return "success";
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
