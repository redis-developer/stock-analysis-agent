package com.redis.stockanalysisagent.session.controller.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record LoginRequest(
        @NotBlank String userId,
        Integer retrievedMemoriesLimit,
        Boolean apiCachingEnabled,
        Boolean semanticCachingEnabled,
        Boolean rateLimitingEnabled,
        Boolean requireApprovalEnabled,
        List<String> approvalRequiredTools
) {
}
