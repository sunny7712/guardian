package com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketState;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.TokenBucketStore;
import com.sunny.guardian.utils.GuardianClock;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Qualifier("redisTransactionTokenBucketStore")
public class RedisTransactionTokenBucketStore implements TokenBucketStore {
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final GuardianClock clock;

    private static final long TOKEN_RESOLUTION_MULTIPLIER = 1000;

    public RedisTransactionTokenBucketStore(RedisTemplate<String, String> redisTemplate,
                                            ObjectMapper objectMapper,
                                            GuardianClock clock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean allowRequest(String key, TokenBucketQuota quota, long costTokens) {
        long bucketCapacityUnits = quota.getBucketCapacity() * TOKEN_RESOLUTION_MULTIPLIER;
        long costTokensUnits = costTokens * TOKEN_RESOLUTION_MULTIPLIER;
        long refillRatePerSec = quota.getRefillRate();

        while(true) {
            try {
                Boolean result = redisTemplate.execute(new SessionCallback<>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public Boolean execute(@NonNull RedisOperations operations) {
                        RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;

                        // 1. WATCH the key to detect changes by other clients
                        ops.watch(key);

                        // 2. READ current state
                        String json = ops.opsForValue().get(key);
                        long now = clock.currentTimeMillis();

                        // 3. COMPUTE new state
                        TokenBucketState existingState = null;
                        if (json != null) {
                            try {
                                existingState = objectMapper.readValue(json, TokenBucketState.class);
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException("Failed to deserialize token bucket state", e);
                            }
                        }

                        // Logic: Initialize or Refill
                        long currentBalance;
                        if (existingState == null) {
                            currentBalance = bucketCapacityUnits;
                        } else {
                            long timeElapsedInMillis = Math.max(0L, now - existingState.lastRefillTimeInEpochMillis());
                            long tokensToAddUnits;
                            try {
                                tokensToAddUnits = Math.multiplyExact(timeElapsedInMillis, refillRatePerSec);
                            } catch (ArithmeticException e) {
                                tokensToAddUnits = bucketCapacityUnits; // Overflow protection
                            }
                            currentBalance = Math.min(bucketCapacityUnits, existingState.availableTokens() + tokensToAddUnits);
                        }

                        // Logic: Consume
                        boolean allowed = false;
                        if (currentBalance >= costTokensUnits) {
                            allowed = true;
                            currentBalance -= costTokensUnits;
                        }

                        // 4. PREPARE new state object
                        TokenBucketState newState = new TokenBucketState(now, currentBalance);
                        String newJson;
                        try {
                            newJson = objectMapper.writeValueAsString(newState);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("Failed to serialize token bucket state", e);
                        }

                        // 5. TRANSACTION BLOCK
                        ops.multi();
                        ops.opsForValue().set(key, newJson);

                        // 6. EXECUTE
                        List<Object> execResults = ops.exec();

                        if (execResults == null || execResults.isEmpty()) {
                            return null; // Return null to trigger the 'while(true)' retry loop
                        }

                        return allowed;
                    }
                });

                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                throw new RuntimeException("Redis Transaction failed", e);
            }
        }
    }
}
