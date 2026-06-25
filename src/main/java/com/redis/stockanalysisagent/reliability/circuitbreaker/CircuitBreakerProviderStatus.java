package com.redis.stockanalysisagent.reliability.circuitbreaker;

import com.redis.stockanalysisagent.reliability.capacity.ProviderCapacityState;

public record CircuitBreakerProviderStatus(
        String providerId,
        String label,
        CircuitBreakerState circuitBreaker,
        ProviderCapacityState capacity,
        long latencySimulationMs,
        boolean failureSimulationEnabled
) {
}
