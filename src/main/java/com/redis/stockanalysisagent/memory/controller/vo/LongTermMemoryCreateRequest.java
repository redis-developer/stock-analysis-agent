package com.redis.stockanalysisagent.memory.controller.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record LongTermMemoryCreateRequest(
        @NotBlank String text,
        String memoryType,
        List<String> topics,
        String sessionId
) {
}
