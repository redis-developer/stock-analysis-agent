package com.redis.stockanalysisagent.session.dto;

public record ChatSessionWorkflowStep(
        String id,
        String label,
        String kind,
        String status,
        Long durationMs,
        String summary,
        String actorType,
        String actorName,
        boolean recovered
) {
}
