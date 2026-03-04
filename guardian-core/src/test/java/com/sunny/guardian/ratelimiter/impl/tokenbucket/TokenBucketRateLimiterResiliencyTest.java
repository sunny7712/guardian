package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.TokenBucketStore;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.sync.TokenBucketConfigProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

class TokenBucketRateLimiterResiliencyTest {

    private TokenBucketStore mockStore;
    private TokenBucketRateLimiterConfig config;
    private TokenBucketRateLimiter rateLimiter;
    private RateLimitRequest request;

    @BeforeEach
    void setUp() {
        mockStore = Mockito.mock(TokenBucketStore.class);
        config = new TokenBucketRateLimiterConfig();
        TokenBucketConfigProvider mockProvider = Mockito.mock(TokenBucketConfigProvider.class);
        Mockito.when(mockProvider.getConfig()).thenReturn(config);

        Map<String, Map<String, TokenBucketQuota>> plans = new HashMap<>();
        Map<String, TokenBucketQuota> quotaMap = new HashMap<>();
        quotaMap.put("default", new TokenBucketQuota(10, 1));
        plans.put("test_plan", quotaMap);
        config.setPlans(plans);

        rateLimiter = new TokenBucketRateLimiter(mockStore, mockProvider, new SimpleMeterRegistry());
        request = new RateLimitRequest("user_123", "test_plan", "default");
    }

    @Test
    void testFailOpen_WhenStoreThrowsException_AllowsRequest() {
        // Arrange: Configure Fail-Open
        config.setFailureMode("open");

        // Force the mock store to simulate a Redis crash
        Mockito.when(mockStore.allowRequest(Mockito.anyString(), Mockito.any(), Mockito.anyLong()))
                .thenThrow(new RuntimeException("Simulated Redis Connection Refused"));

        // Act
        boolean isAllowed = rateLimiter.allow(request);

        // Assert: The system should swallow the error and ALLOW the request
        Assertions.assertTrue(isAllowed, "Expected request to be ALLOWED when store fails in OPEN mode");
    }

    @Test
    void testFailClosed_WhenStoreThrowsException_BlocksRequest() {
        // Arrange: Configure Fail-Closed
        config.setFailureMode("closed");

        // Force the mock store to simulate a Redis timeout
        Mockito.when(mockStore.allowRequest(Mockito.anyString(), Mockito.any(), Mockito.anyLong()))
                .thenThrow(new RuntimeException("Simulated Redis Timeout"));

        // Act
        boolean isAllowed = rateLimiter.allow(request);

        // Assert: The system should swallow the error and BLOCK the request
        Assertions.assertFalse(isAllowed, "Expected request to be BLOCKED when store fails in CLOSED mode");
    }
}
