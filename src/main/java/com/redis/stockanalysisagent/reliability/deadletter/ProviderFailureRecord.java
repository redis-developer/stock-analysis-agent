package com.redis.stockanalysisagent.reliability.deadletter;

public record ProviderFailureRecord(
        String failureId,
        String workflowId,
        String stepId,
        String providerId,
        String providerLabel,
        String cacheName,
        String cacheKey,
        int attempts,
        String reason,
        String failedAt
) {
}
