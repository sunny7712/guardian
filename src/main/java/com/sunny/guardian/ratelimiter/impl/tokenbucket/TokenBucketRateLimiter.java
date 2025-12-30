package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.RateLimiter;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketState;
import com.sunny.guardian.storage.Storage;
import com.sunny.guardian.utils.GuardianClock;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final Storage<TokenBucketState> storage;
    private final TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig;
    private final GuardianClock guardianClock;

    private static final String KEY_DELIMITER = ":";
    private static final long TOKEN_RESOLUTION_MULTIPLIER = 1000;
    private static final long COST_PER_REQUEST = 1 * TOKEN_RESOLUTION_MULTIPLIER;

    public TokenBucketRateLimiter(Storage<TokenBucketState> storage,
                                  TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig,
                                  GuardianClock guardianClock) {
        this.storage = storage;
        this.tokenBucketRateLimiterConfig = tokenBucketRateLimiterConfig;
        this.guardianClock = guardianClock;
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
        long bucketCapacityUnits = quota.getBucketCapacity() * TOKEN_RESOLUTION_MULTIPLIER;
        long refillRatePerSec = quota.getRefillRate();
        String key = constructKey(request);

        boolean[] isAllowed = {false};
        storage.compute(key, (k , existingState) -> {
            long now = guardianClock.currentTimeMillis();
            if (existingState == null) {
                isAllowed[0] = true;
                return new TokenBucketState(now, bucketCapacityUnits - COST_PER_REQUEST);
            }
            long timeElapsedInMillis = Math.max(0L, now - existingState.lastRefillTimeInEpochMillis());
            long tokensToAddUnits;
            try {
                tokensToAddUnits = Math.multiplyExact(timeElapsedInMillis, refillRatePerSec);
            } catch (ArithmeticException e) {
                tokensToAddUnits = bucketCapacityUnits;
            }
            long newBalance = Math.min(bucketCapacityUnits, existingState.availableTokens() + tokensToAddUnits);
            if(newBalance >= COST_PER_REQUEST) {
                isAllowed[0] = true;
                return new TokenBucketState(now, newBalance - COST_PER_REQUEST);
            } else {
                isAllowed[0] = false;
                return new TokenBucketState(now, newBalance);
            }
        });

        return isAllowed[0];
    }

    private String constructKey(RateLimitRequest request) {
        return request.key() + KEY_DELIMITER + request.plan() + KEY_DELIMITER + request.quota();
    }
}
