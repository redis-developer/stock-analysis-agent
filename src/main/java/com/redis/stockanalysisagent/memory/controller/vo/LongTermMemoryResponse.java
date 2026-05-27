package com.redis.stockanalysisagent.memory.controller.vo;

import java.time.Instant;
import java.util.List;

public record LongTermMemoryResponse(
        String id,
        String text,
        String userId,
        String sessionId,
        String namespace,
        String memoryType,
        Instant createdAt,
        Instant updatedAt,
        Instant lastAccessed,
        List<String> topics,
        List<String> entities,
        Double distance
) {
}
