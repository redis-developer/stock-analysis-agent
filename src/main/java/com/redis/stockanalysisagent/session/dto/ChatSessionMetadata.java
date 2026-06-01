package com.redis.stockanalysisagent.session.dto;

import java.util.List;

public record ChatSessionMetadata(
        List<String> tickers,
        List<String> triggeredAgents
) {
    public ChatSessionMetadata {
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
        triggeredAgents = triggeredAgents == null ? List.of() : List.copyOf(triggeredAgents);
    }

    public static ChatSessionMetadata empty() {
        return new ChatSessionMetadata(List.of(), List.of());
    }
}
