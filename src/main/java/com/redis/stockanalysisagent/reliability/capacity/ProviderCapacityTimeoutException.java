package com.redis.stockanalysisagent.reliability.capacity;

public class ProviderCapacityTimeoutException extends RuntimeException {

    private final String providerId;
    private final ProviderCapacityState state;

    public ProviderCapacityTimeoutException(String providerId, ProviderCapacityState state) {
        super("Timed out waiting for provider capacity for " + providerId + ".");
        this.providerId = providerId;
        this.state = state;
    }

    public String providerId() {
        return providerId;
    }

    public ProviderCapacityState state() {
        return state;
    }
}
