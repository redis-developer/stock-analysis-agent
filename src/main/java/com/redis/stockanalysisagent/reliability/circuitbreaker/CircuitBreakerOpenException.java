package com.redis.stockanalysisagent.reliability.circuitbreaker;

public class CircuitBreakerOpenException extends RuntimeException {

    private final String providerId;
    private final CircuitBreakerState state;

    public CircuitBreakerOpenException(String providerId, CircuitBreakerState state) {
        super(message(providerId, state));
        this.providerId = providerId;
        this.state = state;
    }

    public String providerId() {
        return providerId;
    }

    public CircuitBreakerState state() {
        return state;
    }

    private static String message(String providerId, CircuitBreakerState state) {
        if (state.openUntil() != null) {
            return "Circuit breaker is open for provider %s until %s.".formatted(providerId, state.openUntil());
        }
        return "Circuit breaker is open for provider %s.".formatted(providerId);
    }
}
