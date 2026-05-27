package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataAccess;

import java.util.List;

public record ChatExecutionStep(
        String id,
        String label,
        String kind,
        long durationMs,
        String summary,
        TokenUsageSummary tokenUsage,
        Integer loop,
        List<ExternalDataAccess> dataAccesses
) {

    public ChatExecutionStep {
        dataAccesses = dataAccesses == null ? List.of() : List.copyOf(dataAccesses);
    }

    public ChatExecutionStep(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage
    ) {
        this(id, label, kind, durationMs, summary, tokenUsage, null, List.of());
    }

    public ChatExecutionStep(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage,
            Integer loop
    ) {
        this(id, label, kind, durationMs, summary, tokenUsage, loop, List.of());
    }
}
