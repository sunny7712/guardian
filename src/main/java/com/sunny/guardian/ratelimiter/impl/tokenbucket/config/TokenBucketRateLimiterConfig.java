package com.sunny.guardian.ratelimiter.impl.tokenbucket.config;

import com.sunny.guardian.dto.BaseRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "guardian.token-bucket")
public class TokenBucketRateLimiterConfig extends BaseRateLimiterConfig<TokenBucketQuota> {

}
