package com.redis.stockanalysisagent.reliability.simulation;

import com.redis.stockanalysisagent.reliability.circuitbreaker.CircuitBreakerService;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ProviderFailureSimulationService {

    private static final String KEY = "stock-analysis:provider-failure-simulations";
    private static final String ENABLED = "true";

    private final StringRedisTemplate redisTemplate;

    public ProviderFailureSimulationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean enabled(String providerId) {
        Object value = redisTemplate.opsForHash().get(KEY, CircuitBreakerService.normalizeProviderId(providerId));
        return ENABLED.equalsIgnoreCase(value == null ? "" : value.toString());
    }

    public Map<Object, Object> states() {
        HashOperations<String, Object, Object> hashOperations = redisTemplate.opsForHash();
        Map<Object, Object> states = hashOperations.entries(KEY);
        return states == null ? Map.of() : states;
    }

    public void setEnabled(String providerId, boolean enabled) {
        String provider = CircuitBreakerService.normalizeProviderId(providerId);
        if (enabled) {
            redisTemplate.opsForHash().put(KEY, provider, ENABLED);
            return;
        }

        redisTemplate.opsForHash().delete(KEY, provider);
    }

    public void throwIfEnabled(String providerId) {
        String provider = CircuitBreakerService.normalizeProviderId(providerId);
        if (enabled(provider)) {
            throw new IllegalStateException("Simulated provider outage for " + provider + ".");
        }
    }
}
