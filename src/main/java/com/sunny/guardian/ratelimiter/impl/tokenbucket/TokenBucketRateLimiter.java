package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.RateLimiter;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketState;
import com.sunny.guardian.storage.Storage;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final Storage<TokenBucketState> storage;
    private final TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig;

    private static final String KEY_DELIMITER = ":";

    public TokenBucketRateLimiter(Storage<TokenBucketState> storage, TokenBucketRateLimiterConfig tokenBucketRateLimiterConfig) {
        this.storage = storage;
        this.tokenBucketRateLimiterConfig = tokenBucketRateLimiterConfig;

    }

    @Override
    public boolean allow(RateLimitRequest request) {
        long bucketCapacity = tokenBucketRateLimiterConfig.getPlans().get(request.plan()).get(request.quota()).getBucketCapacity();
        long bucketCapacityMillis = bucketCapacity * 1000;
        long refillRate = tokenBucketRateLimiterConfig.getPlans().get(request.plan()).get(request.quota()).getRefillRate();
        String key = request.key() + KEY_DELIMITER + request.plan() + KEY_DELIMITER + request.quota();

        boolean[] isAllowed = {false};
        storage.compute(key, (k , state) -> {
            long timeElapsedInMillis = Math.max(0L, System.currentTimeMillis() - state.getLastRefillTimeInEpochMillis());
            long tokenMillisToAdd;
            try {
                tokenMillisToAdd = Math.multiplyExact(timeElapsedInMillis, refillRate);
            } catch (ArithmeticException e) {
                tokenMillisToAdd = Long.MAX_VALUE; // Cap at max if it overflows
            }
            long currentTokensMillis = Math.min(bucketCapacityMillis, state.getAvailableMillisTokens() + tokenMillisToAdd);
            if(currentTokensMillis >= 1000) {
                currentTokensMillis -= 1000;
                isAllowed[0] = true;
            } else {
                isAllowed[0] = false;
            }
            state.setLastRefillTimeInEpochMillis(System.currentTimeMillis());
            state.setAvailableMillisTokens(currentTokensMillis);
            return state;
        });

        return isAllowed[0];
    }
}
