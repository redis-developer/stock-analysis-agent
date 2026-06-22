package com.redis.stockanalysisagent.reliability;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Service
public class ProviderLatencySimulationService {

    private static final String KEY = "stock-analysis:provider-latency-simulations";

    private final StringRedisTemplate redisTemplate;

    public ProviderLatencySimulationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long delayMs(String providerId) {
        Object value = redisTemplate.opsForHash().get(KEY, CircuitBreakerService.normalizeProviderId(providerId));
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value.toString()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public Map<Object, Object> states() {
        HashOperations<String, Object, Object> hashOperations = redisTemplate.opsForHash();
        Map<Object, Object> states = hashOperations.entries(KEY);
        return states == null ? Map.of() : states;
    }

    public void setDelay(String providerId, Duration delay) {
        String provider = CircuitBreakerService.normalizeProviderId(providerId);
        long delayMs = delay == null ? 0 : Math.max(0, delay.toMillis());
        if (delayMs == 0) {
            redisTemplate.opsForHash().delete(KEY, provider);
            return;
        }

        redisTemplate.opsForHash().put(KEY, provider, String.valueOf(delayMs));
    }

    public void sleepIfConfigured(String providerId) {
        long delayMs = delayMs(providerId);
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
