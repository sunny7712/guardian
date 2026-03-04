package com.sunny.guardian.ratelimiter.impl.tokenbucket.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
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
@ConditionalOnProperty(prefix = "guardian.token-bucket", name = "enabled")
public class TokenBucketConfigProvider implements ConfigReloader {

    private static final String REDIS_KEY = "guardian:config:token-bucket";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicReference<TokenBucketRateLimiterConfig> currentConfig;

    public TokenBucketConfigProvider(RedisTemplate<String, String> redisTemplate,
                                     ObjectMapper objectMapper,
                                     TokenBucketRateLimiterConfig yamlConfig) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.currentConfig = new AtomicReference<>(yamlConfig);
    }

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("Initial Redis config fetch failed for Token Bucket. Falling back to YAML defaults.");
        }
    }

    @Override
    public void reload() throws Exception {
        Map<Object, Object> redisHash = redisTemplate.opsForHash().entries(REDIS_KEY);
        if (redisHash.isEmpty()) return;

        Map<String, Map<String, TokenBucketQuota>> newPlans = new HashMap<>();
        for (Map.Entry<Object, Object> entry : redisHash.entrySet()) {
            Map<String, TokenBucketQuota> quotas = objectMapper.readValue(
                    (String) entry.getValue(), new TypeReference<>() {}
            );
            newPlans.put((String) entry.getKey(), quotas);
        }
        TokenBucketRateLimiterConfig newConfig = new TokenBucketRateLimiterConfig();
        newConfig.setPlans(newPlans);
        newConfig.setFailureMode(currentConfig.get().getFailureMode());

        currentConfig.set(newConfig);
    }

    public TokenBucketRateLimiterConfig getConfig() {
        return currentConfig.get();
    }
}
