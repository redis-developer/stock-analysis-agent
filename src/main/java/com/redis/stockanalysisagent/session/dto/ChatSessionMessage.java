package com.redis.stockanalysisagent.session.dto;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.chat.ChatExecutionStep;
import com.redis.stockanalysisagent.workflow.approval.ToolApproval;

import java.util.List;

public record ChatSessionMessage(
        String role,
        String content,
        String timestamp,
        TokenUsageSummary tokenUsage,
        List<ChatExecutionStep> executionSteps,
        List<String> retrievedMemories,
        boolean fromSemanticCache,
        boolean fromSemanticGuardrail,
        ToolApproval pendingApproval,
        String workflowId
) {
    public ChatSessionMessage(String role, String content) {
        this(role, content, null, null, List.of(), List.of(), false, false, null, null);
    }

    public ChatSessionMessage(String role, String content, String timestamp) {
        this(role, content, timestamp, null, List.of(), List.of(), false, false, null, null);
    }

    public ChatSessionMessage(
            String role,
            String content,
            String timestamp,
            TokenUsageSummary tokenUsage,
            List<ChatExecutionStep> executionSteps
    ) {
        this(role, content, timestamp, tokenUsage, executionSteps, List.of(), false, false, null, null);
    }

    public ChatSessionMessage(
            String role,
            String content,
            String timestamp,
            List<ChatExecutionStep> executionSteps
    ) {
        this(role, content, timestamp, null, executionSteps, List.of(), false, false, null, null);
    }

    public ChatSessionMessage {
        executionSteps = executionSteps == null ? List.of() : List.copyOf(executionSteps);
        retrievedMemories = retrievedMemories == null ? List.of() : List.copyOf(retrievedMemories);
    }
}
