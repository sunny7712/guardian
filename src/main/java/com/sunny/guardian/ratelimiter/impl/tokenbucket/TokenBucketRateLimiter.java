package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.sunny.guardian.ratelimiter.RateLimiter;

public class TokenBucketRateLimiter implements RateLimiter {

    public TokenBucketRateLimiter() {
    }


    @Override
    public boolean allow(String key) {
        return false;
    }
}
