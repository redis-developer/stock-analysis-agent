package com.redis.stockanalysisagent.reliability;

public record ProviderCapacityState(
        String providerId,
        int limit,
        long activeCount,
        long waitingCount,
        long timeoutCount,
        boolean capacityAvailable
) {
}
