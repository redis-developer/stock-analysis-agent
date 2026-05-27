package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalApiUsageSnapshot;

import java.util.List;

public record ChatResponse(
        String userId,
        String sessionId,
        String conversationId,
        String response,
        List<String> retrievedMemories,
        boolean fromSemanticCache,
        boolean fromSemanticGuardrail,
        TokenUsageSummary tokenUsage,
        List<ChatExecutionStep> executionSteps,
        int retrievedMemoriesLimit,
        boolean apiCachingEnabled,
        boolean semanticCachingEnabled,
        boolean rateLimitingEnabled,
        ExternalApiUsageSnapshot providerUsage,
        long responseTimeMs
) {
}
