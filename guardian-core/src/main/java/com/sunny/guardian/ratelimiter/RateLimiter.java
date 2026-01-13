package com.sunny.guardian.ratelimiter;

import com.sunny.guardian.dto.RateLimitRequest;

public interface RateLimiter {
    boolean allow(RateLimitRequest rateLimitRequest);
}
