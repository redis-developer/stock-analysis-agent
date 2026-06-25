package com.redis.stockanalysisagent.reliability.circuitbreaker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "stock-analysis.circuit-breaker")
public class CircuitBreakerProperties {

    private boolean enabled = true;
    private int failureThreshold = 3;
    private Duration openDuration = Duration.ofSeconds(30);
    private Duration probeTimeout = Duration.ofSeconds(10);
    private Duration stateTtl = Duration.ofDays(7);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = Math.max(1, failureThreshold);
    }

    public Duration getOpenDuration() {
        return openDuration;
    }

    public void setOpenDuration(Duration openDuration) {
        this.openDuration = openDuration != null ? openDuration : Duration.ofSeconds(30);
    }

    public Duration getProbeTimeout() {
        return probeTimeout;
    }

    public void setProbeTimeout(Duration probeTimeout) {
        this.probeTimeout = probeTimeout != null ? probeTimeout : Duration.ofSeconds(10);
    }

    public Duration getStateTtl() {
        return stateTtl;
    }

    public void setStateTtl(Duration stateTtl) {
        this.stateTtl = stateTtl != null ? stateTtl : Duration.ofDays(7);
    }
}
