package com.redis.stockanalysisagent.session.controller.vo;

import com.redis.stockanalysisagent.cache.ExternalApiUsageSnapshot;

import java.util.List;

public record ChatContextResponse(
        int defaultRetrievedMemoriesLimit,
        String userId,
        int retrievedMemoriesLimit,
        boolean apiCachingEnabled,
        boolean semanticCachingEnabled,
        boolean rateLimitingEnabled,
        boolean requireApprovalEnabled,
        List<String> approvalRequiredTools,
        long rateLimitLimit,
        long rateLimitRemaining,
        ExternalApiUsageSnapshot providerUsage,
        boolean sessionManagementEnabled
) {
    public ChatContextResponse {
        approvalRequiredTools = approvalRequiredTools == null ? List.of() : List.copyOf(approvalRequiredTools);
    }
}
