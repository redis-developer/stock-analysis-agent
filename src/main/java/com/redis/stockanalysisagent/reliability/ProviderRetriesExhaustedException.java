package com.redis.stockanalysisagent.reliability;

public class ProviderRetriesExhaustedException extends RuntimeException {

    private final String providerId;
    private final int attempts;

    public ProviderRetriesExhaustedException(String providerId, int attempts, RuntimeException cause) {
        super(message(providerId, attempts, cause), cause);
        this.providerId = providerId;
        this.attempts = Math.max(1, attempts);
    }

    public String providerId() {
        return providerId;
    }

    public int attempts() {
        return attempts;
    }

    private static String message(String providerId, int attempts, RuntimeException cause) {
        if (cause == null) {
            return "Provider " + providerId + " failed after " + Math.max(1, attempts) + " attempts.";
        }
        String reason = cause.getMessage();
        if (reason == null || reason.isBlank()) {
            reason = cause.getClass().getSimpleName();
        }
        return "Provider " + providerId + " failed after " + Math.max(1, attempts) + " attempts: " + reason;
    }
}
