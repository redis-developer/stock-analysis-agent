package com.redis.stockanalysisagent.reliability.capacity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "stock-analysis.provider-capacity")
public class ProviderCapacityProperties {

    private boolean enabled = true;
    private int defaultLimit = 2;
    private Duration permitTtl = Duration.ofSeconds(30);
    private Duration waitTimeout = Duration.ofSeconds(5);
    private Duration retryInterval = Duration.ofMillis(100);
    private Duration stateTtl = Duration.ofDays(7);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = Math.max(1, defaultLimit);
    }

    public Duration getPermitTtl() {
        return permitTtl;
    }

    public void setPermitTtl(Duration permitTtl) {
        this.permitTtl = permitTtl != null ? permitTtl : Duration.ofSeconds(30);
    }

    public Duration getWaitTimeout() {
        return waitTimeout;
    }

    public void setWaitTimeout(Duration waitTimeout) {
        this.waitTimeout = waitTimeout != null ? waitTimeout : Duration.ofSeconds(5);
    }

    public Duration getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval != null ? retryInterval : Duration.ofMillis(100);
    }

    public Duration getStateTtl() {
        return stateTtl;
    }

    public void setStateTtl(Duration stateTtl) {
        this.stateTtl = stateTtl != null ? stateTtl : Duration.ofDays(7);
    }
}
