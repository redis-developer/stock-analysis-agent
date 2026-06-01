package com.redis.stockanalysisagent.session.dto;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.chat.ChatExecutionStep;

import java.util.List;

public record ChatSessionMessage(
        String role,
        String content,
        String timestamp,
        TokenUsageSummary tokenUsage,
        List<ChatExecutionStep> executionSteps
) {
    public ChatSessionMessage(String role, String content) {
        this(role, content, null, null, List.of());
    }

    public ChatSessionMessage(String role, String content, String timestamp) {
        this(role, content, timestamp, null, List.of());
    }

    public ChatSessionMessage(
            String role,
            String content,
            String timestamp,
            List<ChatExecutionStep> executionSteps
    ) {
        this(role, content, timestamp, null, executionSteps);
    }

    public ChatSessionMessage {
        executionSteps = executionSteps == null ? List.of() : List.copyOf(executionSteps);
    }
}
