package com.redis.stockanalysisagent.agent;

import com.redis.stockanalysisagent.cache.ExternalDataAccess;

import java.util.List;

public record AgentExecution(
        AgentType agentType,
        String ticker,
        String summary,
        long durationMs,
        TokenUsageSummary tokenUsage,
        Integer loop,
        List<ExternalDataAccess> dataAccesses
) {

    public AgentExecution {
        dataAccesses = dataAccesses == null ? List.of() : List.copyOf(dataAccesses);
    }

    public AgentExecution(
            AgentType agentType,
            String ticker,
            String summary,
            long durationMs,
            TokenUsageSummary tokenUsage
    ) {
        this(agentType, ticker, summary, durationMs, tokenUsage, null, List.of());
    }

    public AgentExecution(
            AgentType agentType,
            String ticker,
            String summary,
            long durationMs,
            TokenUsageSummary tokenUsage,
            List<ExternalDataAccess> dataAccesses
    ) {
        this(agentType, ticker, summary, durationMs, tokenUsage, null, dataAccesses);
    }
}
