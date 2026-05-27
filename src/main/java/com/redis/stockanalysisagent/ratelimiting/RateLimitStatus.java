package com.redis.stockanalysisagent.ratelimiting;

public record RateLimitStatus(
        long remainingTokens,
        long limit
) {
}
