package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalApiUsageSnapshot;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;

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
        long responseTimeMs,
        List<String> tickers,
        List<String> triggeredAgents,
        String workflowId,
        WorkflowStatus workflowStatus
) {
    public ChatResponse {
        retrievedMemories = retrievedMemories == null ? List.of() : List.copyOf(retrievedMemories);
        executionSteps = executionSteps == null ? List.of() : List.copyOf(executionSteps);
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
        triggeredAgents = triggeredAgents == null ? List.of() : List.copyOf(triggeredAgents);
    }
}
