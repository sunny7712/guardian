package com.sunny.guardian.ratelimiter.impl.tokenbucket.config;

import com.sunny.guardian.config.BaseRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@EqualsAndHashCode(callSuper = true)
@Data
@Configuration
@ConfigurationProperties(prefix = "guardian.token-bucket")
public class TokenBucketRateLimiterConfig extends BaseRateLimiterConfig<TokenBucketQuota> {

    private String failureMode;

}
