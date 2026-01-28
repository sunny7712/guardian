package com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.impl;

import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketState;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.TokenBucketStore;
import com.sunny.guardian.utils.GuardianClock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTokenBucketStore implements TokenBucketStore {

    private final Map<String, TokenBucketState> stateMap = new ConcurrentHashMap<>();
    private final GuardianClock clock;
    private static final long TOKEN_RESOLUTION_MULTIPLIER = 1000;
    private static final long COST_PER_REQUEST = 1 * TOKEN_RESOLUTION_MULTIPLIER;


    public InMemoryTokenBucketStore(GuardianClock clock) {
        this.clock = clock;
    }

    @Override
    public boolean allowRequest(String key, TokenBucketQuota quota, long costTokens) {

        long bucketCapacityUnits = quota.getBucketCapacity() * TOKEN_RESOLUTION_MULTIPLIER;
        long refillRatePerSec = quota.getRefillRate();

        boolean[] isAllowed = {false};
        stateMap.compute(key, (k , existingState) -> {
            long now = clock.currentTimeMillis();
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

}
