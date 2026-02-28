package com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.storage.impl;

import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.dto.SlidingWindowQuota;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.storage.SlidingWindowStore;
import com.sunny.guardian.utils.GuardianClock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class RedisLuaSlidingWindowStore implements SlidingWindowStore {

    private final RedisTemplate<String, String> redisTemplate;
    private final GuardianClock clock;
    private final DefaultRedisScript<Long> redisScript;

    public RedisLuaSlidingWindowStore(RedisTemplate<String, String> redisTemplate, GuardianClock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.redisScript = new DefaultRedisScript<>();
        this.redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/sliding_window_counter.lua")));
        this.redisScript.setResultType(Long.class);
    }

    public boolean allowRequest(String key, SlidingWindowQuota slidingWindowQuota, long cost) {

        long nowInMillis = clock.currentTimeMillis();

        List<String> keys = Collections.singletonList(key);

        Long result = redisTemplate.execute(
                redisScript,
                keys,
                String.valueOf(slidingWindowQuota.getRequestLimit()),
                String.valueOf(slidingWindowQuota.getWindowSizeInSeconds()),
                String.valueOf(nowInMillis),
                String.valueOf(cost)
        );
        return result != null && result == 1L;
    }
}
