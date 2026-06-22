package com.redis.stockanalysisagent.reliability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "stock-analysis.provider-latency-simulation")
public class ProviderLatencySimulationProperties {

    private Duration defaultDelay = Duration.ofSeconds(20);

    public Duration getDefaultDelay() {
        return defaultDelay;
    }

    public void setDefaultDelay(Duration defaultDelay) {
        this.defaultDelay = defaultDelay != null ? defaultDelay : Duration.ofSeconds(20);
    }
}
