package com.sunny.guardian.sync;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnBean(ConfigReloader.class)
public class GuardianConfigScheduler {

    private final List<ConfigReloader> reloaders;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastSuccessfulUpdateTimestamp = new AtomicLong(0);

    public GuardianConfigScheduler(List<ConfigReloader> reloaders, MeterRegistry meterRegistry) {
        this.reloaders = reloaders;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("guardian.config.last_updated_timestamp", lastSuccessfulUpdateTimestamp);
    }

    @Scheduled(fixedRateString = "${guardian.dynamic-config.refresh-rate:60000}")
    public void poll() {
        if (reloaders == null || reloaders.isEmpty()) {
            return;
        }
        boolean allSuccess = true;
        for (ConfigReloader reloader : reloaders) {
            String providerName = reloader.getClass().getSimpleName();
            try {
                reloader.reload();
                meterRegistry.counter("guardian.config.reload",
                        "provider", providerName,
                        "status", "success").increment();
            } catch (Exception e) {
                allSuccess = false;
                meterRegistry.counter("guardian.config.reload",
                        "provider", providerName,
                        "status", "error").increment();
                log.error("Failed to reload configuration for {}. Using last known good state. Error: {}",
                        providerName, e.getMessage());
            }
        }
        if (allSuccess) {
            lastSuccessfulUpdateTimestamp.set(System.currentTimeMillis());
            log.debug("Guardian configurations successfully synchronized.");
        }
    }

}
