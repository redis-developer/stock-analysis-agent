package com.redis.stockanalysisagent.session.controller.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChatSettingsRequest(
        @NotNull
        @Min(1)
        @Max(20)
        Integer retrievedMemoriesLimit,
        Boolean apiCachingEnabled,
        Boolean semanticCachingEnabled,
        Boolean rateLimitingEnabled
) {
}
