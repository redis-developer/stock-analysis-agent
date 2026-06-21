package com.redis.stockanalysisagent.workflow;

import java.time.Instant;

public record WorkflowCheckpoint(
        String streamId,
        Instant timestamp,
        String checkpointId,
        String stepId,
        String sourceEventType,
        String actorType,
        String actorName,
        String summary,
        String inputBytes,
        String inputPayload,
        String outputBytes,
        String outputPayload
) {
}
