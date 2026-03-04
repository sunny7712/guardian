package com.sunny.guardian.ratelimiter.impl.tokenbucket;

import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.RateLimiter;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.config.TokenBucketRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.storage.TokenBucketStore;
import com.sunny.guardian.ratelimiter.impl.tokenbucket.sync.TokenBucketConfigProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "guardian.token-bucket", name = "enabled")
public class TokenBucketRateLimiter implements RateLimiter {

    public static final String ALLOWED = "allowed";
    public static final String BLOCKED = "blocked";
    public static final String ERROR = "error";

    private static final String KEY_DELIMITER = ":";
    private static final int DEFAULT_COST_OF_TOKENS = 1;


    private final TokenBucketStore store;
    private final TokenBucketConfigProvider configProvider;
    private final MeterRegistry meterRegistry;



    public TokenBucketRateLimiter(TokenBucketStore tokenBucketStore,
                                  TokenBucketConfigProvider configProvider,
                                  MeterRegistry meterRegistry) {
        this.store = tokenBucketStore;
        this.configProvider = configProvider;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean allow(RateLimitRequest request) {

        Timer.Sample sample = Timer.start(meterRegistry);

        String decision = ALLOWED;

        try {
            TokenBucketRateLimiterConfig currentConfig = configProvider.getConfig();
            var planMap = currentConfig.getPlans().get(request.plan());
            if (planMap == null) {
                throw new IllegalArgumentException("Invalid plan: " + request.plan());
            }

            TokenBucketQuota quota = planMap.get(request.quota());
            if (quota == null) {
                throw new IllegalArgumentException("Invalid quota: " + request.quota());
            }
            String key = constructKey(request);
            boolean allowed =  store.allowRequest(key, quota, DEFAULT_COST_OF_TOKENS);
            decision = allowed ? ALLOWED : BLOCKED;
            return allowed;
        } catch (Exception e) {
            decision = ERROR;
            log.error("Rate Limiter Storage failed for key: {}. Error: {}", request.key(), e.getMessage());

            // Check the configured strategy
            String mode = configProvider.getConfig().getFailureMode();

            meterRegistry.counter("guardian.ratelimit.fallback",
                    "plan", request.plan(),
                    "mode", mode.toUpperCase()
            ).increment();

            if ("open".equalsIgnoreCase(mode)) {
                log.warn("Failure Mode is OPEN. Allowing request despite failure.");
                return true; // FAIL-OPEN
            } else {
                log.warn("Failure Mode is CLOSED. Blocking request due to failure.");
                return false; // FAIL-CLOSED
            }

        } finally {
            sample.stop(Timer.builder("guardian.ratelimit.logic")
                    .tag("algorithm", "token_bucket")
                    .description("Time taken to execute rate limit logic")
                    .tag("plan", request.plan())
                    .tag("quota", request.quota())
                    .tag("result", decision)
                    .register(meterRegistry));
        }

    }

    private String constructKey(RateLimitRequest request) {
        return request.key() + KEY_DELIMITER + request.plan() + KEY_DELIMITER + request.quota();
    }
}
