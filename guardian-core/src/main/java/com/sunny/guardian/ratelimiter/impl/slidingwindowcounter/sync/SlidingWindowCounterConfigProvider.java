package com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.config.SlidingWindowRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.dto.SlidingWindowQuota;
import com.sunny.guardian.sync.ConfigReloader;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "guardian.sliding-window-counter", name = "enabled")
public class SlidingWindowCounterConfigProvider implements ConfigReloader {

    private static final String REDIS_KEY = "guardian:config:sliding-window-counter";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicReference<SlidingWindowRateLimiterConfig> currentConfig;

    public SlidingWindowCounterConfigProvider(RedisTemplate<String, String> redisTemplate,
                                              ObjectMapper objectMapper,
                                              SlidingWindowRateLimiterConfig yamlConfig) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.currentConfig = new AtomicReference<>(yamlConfig);
    }

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("Initial Redis config fetch failed for Sliding Window Counter. Falling back to YAML defaults.");
        }
    }

    @Override
    public void reload() throws Exception {
        Map<Object, Object> redisHash = redisTemplate.opsForHash().entries(REDIS_KEY);
        if (redisHash.isEmpty()) return;

        Map<String, Map<String, SlidingWindowQuota>> newPlans = new HashMap<>();
        for (Map.Entry<Object, Object> entry : redisHash.entrySet()) {
            Map<String, SlidingWindowQuota> quotas = objectMapper.readValue(
                    (String) entry.getValue(), new TypeReference<>() {}
            );
            newPlans.put((String) entry.getKey(), quotas);
        }
        SlidingWindowRateLimiterConfig newConfig = new SlidingWindowRateLimiterConfig();
        newConfig.setPlans(newPlans);
        newConfig.setFailureMode(currentConfig.get().getFailureMode());

        currentConfig.set(newConfig);
    }

    public SlidingWindowRateLimiterConfig getConfig() {
        return currentConfig.get();
    }
}
