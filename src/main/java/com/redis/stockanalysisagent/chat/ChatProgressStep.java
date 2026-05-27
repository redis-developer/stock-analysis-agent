package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.cache.ExternalDataAccess;

import java.util.List;

public record ChatProgressStep(
        String id,
        String label,
        String kind,
        String status,
        Long durationMs,
        String summary,
        Integer loop,
        List<ExternalDataAccess> dataAccesses
) {

    public ChatProgressStep {
        dataAccesses = dataAccesses == null ? List.of() : List.copyOf(dataAccesses);
    }
}
