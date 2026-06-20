package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataAccess;

import java.util.List;

public record ChatProgressStep(
        String id,
        String label,
        String kind,
        String status,
        Long durationMs,
        String summary,
        TokenUsageSummary tokenUsage,
        Integer loop,
        List<ExternalDataAccess> dataAccesses,
        String actorType,
        String actorName,
        ChatProgressMetadata metadata
) {
    public ChatProgressStep {
        dataAccesses = dataAccesses == null ? List.of() : List.copyOf(dataAccesses);
        actorType = clean(actorType);
        actorName = clean(actorName);
        metadata = metadata == null ? ChatProgressMetadata.empty() : metadata;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
