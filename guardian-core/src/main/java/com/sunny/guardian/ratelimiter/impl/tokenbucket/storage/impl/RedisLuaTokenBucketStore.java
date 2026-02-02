package com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.impl;

import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.TokenBucketStore;
import com.sunny.guardian.utils.GuardianClock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.Collections;
import java.util.List;

public class RedisLuaTokenBucketStore implements TokenBucketStore {

    private final RedisTemplate<String, String> redisTemplate;
    private final GuardianClock clock;
    private final DefaultRedisScript<Long> redisScript;

    private static final long TOKEN_RESOLUTION_MULTIPLIER = 1000;
    private static final String REDIS_LUA_SCRIPT_TOKEN_BUCKET_CLASS_PATH = "scripts/token_bucket_hash.lua";

    public RedisLuaTokenBucketStore(RedisTemplate<String, String> redisTemplate,
                                    GuardianClock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.redisScript = new DefaultRedisScript<>();
        this.redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(REDIS_LUA_SCRIPT_TOKEN_BUCKET_CLASS_PATH)));
        this.redisScript.setResultType(Long.class);
    }

    @Override
    public boolean allowRequest(String key, TokenBucketQuota quota, long costTokens) {
        long bucketCapacityUnits = quota.getBucketCapacity() * TOKEN_RESOLUTION_MULTIPLIER;
        long costTokensUnits = costTokens * TOKEN_RESOLUTION_MULTIPLIER;
        long refillRatePerSec = quota.getRefillRate();
        long now = clock.currentTimeMillis();

        List<String> keys = Collections.singletonList(key);

        Long result = redisTemplate.execute(
                redisScript,
                keys,
                String.valueOf(bucketCapacityUnits),
                String.valueOf(refillRatePerSec),
                String.valueOf(costTokensUnits),
                String.valueOf(now)
        );

        return result != null && result == 1L;
    }
}
