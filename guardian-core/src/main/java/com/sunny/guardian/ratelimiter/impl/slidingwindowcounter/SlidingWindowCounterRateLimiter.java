package com.sunny.guardian.ratelimiter.impl.slidingwindowcounter;

import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.RateLimiter;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.config.SlidingWindowRateLimiterConfig;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.dto.SlidingWindowQuota;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.storage.SlidingWindowStore;
import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.sync.SlidingWindowCounterConfigProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("slidingWindowCounterRateLimiter")
@Slf4j
@ConditionalOnProperty(prefix = "guardian.sliding-window-counter", name = "enabled")
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    public static final String ALLOWED = "allowed";
    public static final String BLOCKED = "blocked";
    public static final String ERROR = "error";

    private final SlidingWindowStore store;
    private final SlidingWindowCounterConfigProvider configProvider;
    private final MeterRegistry meterRegistry;

    public SlidingWindowCounterRateLimiter(SlidingWindowStore store,
                                           SlidingWindowCounterConfigProvider configProvider,
                                           MeterRegistry meterRegistry) {
        this.store = store;
        this.configProvider = configProvider;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean allow(RateLimitRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String decision = ALLOWED;

        try {
            SlidingWindowRateLimiterConfig currentConfig = configProvider.getConfig();
            var planMap = currentConfig.getPlans().get(request.plan());
            if (planMap == null) throw new IllegalArgumentException("Invalid plan: " + request.plan());

            SlidingWindowQuota quota = planMap.get(request.quota());
            if (quota == null) throw new IllegalArgumentException("Invalid quota: " + request.quota());

            String key = request.key() + ":" + request.plan() + ":" + request.quota();

            boolean allowed = store.allowRequest(key, quota, 1);
            decision = allowed ? ALLOWED : BLOCKED;
            return allowed;
        } catch (Exception e) {
            decision = ERROR;
            log.error("Sliding Window Storage failed for key: {}. Error: {}", request.key(), e.getMessage());

            String mode = configProvider.getConfig().getFailureMode();
            meterRegistry.counter("guardian.ratelimit.fallback", "plan", request.plan(), "mode", mode.toUpperCase()).increment();

            if ("open".equalsIgnoreCase(mode)) {
                log.warn("Failure Mode is OPEN. Allowing request despite failure.");
                return true; // FAIL-OPEN
            } else {
                log.warn("Failure Mode is CLOSED. Blocking request due to failure.");
                return false; // FAIL-CLOSED
            }
        } finally {
            sample.stop(Timer.builder("guardian.ratelimit.logic")
                    .tag("algorithm", "sliding_window")
                    .description("Time taken to execute rate limit logic")
                    .tag("plan", request.plan())
                    .tag("quota", request.quota())
                    .tag("result", decision)
                    .register(meterRegistry));
        }
    }
}
