package com.redis.stockanalysisagent.reliability;

public record CircuitBreakerProviderStatus(
        String providerId,
        String label,
        CircuitBreakerState circuitBreaker,
        ProviderCapacityState capacity,
        long latencySimulationMs,
        boolean failureSimulationEnabled
) {
}
