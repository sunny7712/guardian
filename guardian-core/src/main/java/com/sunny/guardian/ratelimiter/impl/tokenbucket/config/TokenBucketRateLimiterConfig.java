package com.sunny.guardian.ratelimiter.impl.tokenbucket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.guardian.config.BaseRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketState;
import com.sunny.guardian.storage.Storage;
import com.sunny.guardian.storage.impl.RedisTransactionStorage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@ConfigurationProperties(prefix = "guardian.token-bucket")
public class TokenBucketRateLimiterConfig extends BaseRateLimiterConfig<TokenBucketQuota> {

    @Bean("tokenBucketRedisStorage")
    public Storage<TokenBucketState> tokenBucketRedisStorage(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper
    ) {
        return new RedisTransactionStorage<>(
                redisTemplate,
                objectMapper,
                TokenBucketState.class
        );
    }


}
