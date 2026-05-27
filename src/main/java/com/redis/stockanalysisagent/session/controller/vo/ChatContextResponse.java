package com.redis.stockanalysisagent.session.controller.vo;

import com.redis.stockanalysisagent.cache.ExternalApiUsageSnapshot;

public record ChatContextResponse(
        int defaultRetrievedMemoriesLimit,
        String userId,
        int retrievedMemoriesLimit,
        boolean apiCachingEnabled,
        boolean semanticCachingEnabled,
        boolean rateLimitingEnabled,
        long rateLimitLimit,
        long rateLimitRemaining,
        ExternalApiUsageSnapshot providerUsage,
        boolean sessionManagementEnabled
) {
}
