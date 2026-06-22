package com.redis.stockanalysisagent.chat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatRequest(
        String sessionId,
        @NotBlank String message,
        @Min(1)
        @Max(20)
        Integer retrievedMemoriesLimit,
        Boolean apiCachingEnabled,
        Boolean semanticCachingEnabled,
        Boolean rateLimitingEnabled,
        Boolean requireApprovalEnabled,
        List<String> approvalRequiredTools,
        String clientRequestId
) {
}
