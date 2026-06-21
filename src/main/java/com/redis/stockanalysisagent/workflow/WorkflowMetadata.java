package com.redis.stockanalysisagent.workflow;

import java.time.Instant;

public record WorkflowMetadata(
        String workflowId,
        String clientRequestId,
        String userId,
        String sessionId,
        String conversationId,
        WorkflowStatus status,
        String previousWorkflowId,
        Integer turnIndex,
        String ownerId,
        Instant leaseUntil,
        Long leaseVersion,
        Integer attempt,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt,
        String failureReason
) {
}
