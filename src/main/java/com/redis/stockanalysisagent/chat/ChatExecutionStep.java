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
        List<ExternalDataAccess> dataAccesses,
        String actorType,
        String actorName
) {

    public ChatExecutionStep {
        dataAccesses = dataAccesses == null ? List.of() : List.copyOf(dataAccesses);
        actorType = clean(actorType);
        actorName = clean(actorName);
    }

    public ChatExecutionStep(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage
    ) {
        this(
                id,
                label,
                kind,
                durationMs,
                summary,
                tokenUsage,
                null,
                List.of(),
                defaultActorType(kind),
                defaultActorName(kind)
        );
    }

    private static String defaultActorType(String kind) {
        return WorkflowProgress.KIND_AGENT.equals(kind)
                ? WorkflowProgress.KIND_AGENT
                : WorkflowProgress.ACTOR_TYPE_SYSTEM;
    }

    private static String defaultActorName(String kind) {
        return WorkflowProgress.KIND_AGENT.equals(kind) ? "" : WorkflowProgress.ACTOR_SYSTEM;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
