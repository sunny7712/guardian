package com.sunny.guardian.ratelimiter;

public interface RateLimiter {
    boolean allow(String key);
}
