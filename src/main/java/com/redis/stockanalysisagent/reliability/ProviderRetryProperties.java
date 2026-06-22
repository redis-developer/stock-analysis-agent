package com.redis.stockanalysisagent.reliability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "stock-analysis.provider-retries")
public class ProviderRetryProperties {

    private boolean enabled = true;
    private int maxAttempts = 2;
    private Duration backoff = Duration.ofMillis(250);
    private Duration deadLetterTtl = Duration.ofDays(7);
    private int deadLetterReadLimit = 25;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public Duration getBackoff() {
        return backoff;
    }

    public void setBackoff(Duration backoff) {
        this.backoff = backoff != null ? backoff : Duration.ofMillis(250);
    }

    public Duration getDeadLetterTtl() {
        return deadLetterTtl;
    }

    public void setDeadLetterTtl(Duration deadLetterTtl) {
        this.deadLetterTtl = deadLetterTtl != null ? deadLetterTtl : Duration.ofDays(7);
    }

    public int getDeadLetterReadLimit() {
        return deadLetterReadLimit;
    }

    public void setDeadLetterReadLimit(int deadLetterReadLimit) {
        this.deadLetterReadLimit = Math.max(1, deadLetterReadLimit);
    }
}
