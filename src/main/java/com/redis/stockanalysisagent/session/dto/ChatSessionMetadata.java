package com.redis.stockanalysisagent.session.dto;

import java.util.List;

public record ChatSessionMetadata(
        List<String> tickers,
        List<String> triggeredAgents,
        String latestWorkflowId,
        String latestWorkflowStatus,
        String latestWorkflowMode,
        String recoveredFromWorkflowId,
        String replayCheckpointId,
        String recoveredByWorkflowId,
        String failureReason,
        List<ChatSessionWorkflowStep> latestWorkflowSteps
) {
    public ChatSessionMetadata(List<String> tickers, List<String> triggeredAgents) {
        this(tickers, triggeredAgents, null, null);
    }

    public ChatSessionMetadata(
            List<String> tickers,
            List<String> triggeredAgents,
            String latestWorkflowId,
            String latestWorkflowStatus
    ) {
        this(tickers, triggeredAgents, latestWorkflowId, latestWorkflowStatus, null, null, null, null, null, List.of());
    }

    public ChatSessionMetadata {
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
        triggeredAgents = triggeredAgents == null ? List.of() : List.copyOf(triggeredAgents);
        latestWorkflowSteps = latestWorkflowSteps == null ? List.of() : List.copyOf(latestWorkflowSteps);
    }

    public static ChatSessionMetadata empty() {
        return new ChatSessionMetadata(List.of(), List.of(), null, null);
    }
}
