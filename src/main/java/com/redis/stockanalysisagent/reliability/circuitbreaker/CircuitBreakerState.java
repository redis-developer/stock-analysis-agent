package com.redis.stockanalysisagent.reliability.circuitbreaker;

import java.time.Instant;

public record CircuitBreakerState(
        String providerId,
        String state,
        long failureCount,
        long blockedCallCount,
        Instant openedAt,
        Instant openUntil,
        Instant halfOpenAt,
        Instant lastFailureAt,
        Instant lastSuccessAt,
        String reason,
        boolean callsAllowed
) {
}
