package com.redis.stockanalysisagent.workflow.approval;

import java.time.Instant;

public record ToolApproval(
        String approvalId,
        String workflowId,
        String activeWorkflowId,
        String userId,
        String sessionId,
        String conversationId,
        String toolName,
        String agentType,
        String ticker,
        String question,
        String arguments,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant decidedAt,
        String resumedWorkflowId
) {
    public boolean pending() {
        return "PENDING".equals(status);
    }

    public boolean approved() {
        return "APPROVED".equals(status);
    }

    public boolean rejected() {
        return "REJECTED".equals(status);
    }
}
