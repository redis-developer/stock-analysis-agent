package com.redis.stockanalysisagent.ratelimiting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.rate-limiting")
public class RateLimitingProperties {

    private boolean enabled = true;
    private int requestsPerMinute = 6;
    private Duration refillPeriod = Duration.ofMinutes(1);
    private String redisKeyPrefix = "stock-analysis:rate-limit:user";
    private List<String> protectedPaths = List.of("/api/chat", "/api/chat/stream");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public Duration getRefillPeriod() {
        return refillPeriod;
    }

    public void setRefillPeriod(Duration refillPeriod) {
        this.refillPeriod = refillPeriod;
    }

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    public List<String> getProtectedPaths() {
        return protectedPaths;
    }

    public void setProtectedPaths(List<String> protectedPaths) {
        this.protectedPaths = new ArrayList<>(protectedPaths);
    }

    public int requireRequestsPerMinute() {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("Rate limit requests per minute must be greater than zero.");
        }
        return requestsPerMinute;
    }

    public Duration requireRefillPeriod() {
        if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Rate limit refill period must be positive.");
        }
        return refillPeriod;
    }

    public String requireRedisKeyPrefix() {
        if (redisKeyPrefix == null || redisKeyPrefix.isBlank()) {
            throw new IllegalArgumentException("Rate limit Redis key prefix is required.");
        }
        return redisKeyPrefix.trim();
    }
}
