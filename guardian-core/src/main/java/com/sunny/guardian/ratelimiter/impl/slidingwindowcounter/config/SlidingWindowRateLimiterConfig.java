package com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.config;

import com.sunny.guardian.config.BaseRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.dto.SlidingWindowQuota;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@EqualsAndHashCode(callSuper = true)
@Data
@Configuration
@ConfigurationProperties(prefix = "guardian.sliding-window")
public class SlidingWindowRateLimiterConfig extends BaseRateLimiterConfig<SlidingWindowQuota> {
    private String failureMode = "closed";
}
