package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.RateLimiter;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.TokenBucketStore;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final TokenBucketStore store;
    private final TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig;

    private static final String KEY_DELIMITER = ":";
    private static final int DEFAULT_COST_OF_TOKENS = 1;

    public TokenBucketRateLimiter(TokenBucketStore tokenBucketStore,
                                  TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig) {
        this.store = tokenBucketStore;
        this.tokenBucketRateLimiterConfig = tokenBucketRateLimiterConfig;
    }

    @Override
    public boolean allow(RateLimitRequest request) {
        var planMap = tokenBucketRateLimiterConfig.getPlans().get(request.plan());
        if (planMap == null) {
            throw new IllegalArgumentException("Invalid plan: " + request.plan());
        }

        TokenBucketQuota quota = planMap.get(request.quota());
        if (quota == null) {
            throw new IllegalArgumentException("Invalid quota: " + request.quota());
        }
        String key = constructKey(request);
        return store.allowRequest(key, quota, DEFAULT_COST_OF_TOKENS);
    }

    private String constructKey(RateLimitRequest request) {
        return request.key() + KEY_DELIMITER + request.plan() + KEY_DELIMITER + request.quota();
    }
}
