package com.redis.stockanalysisagent.ratelimiting;

import java.time.Duration;

public record RateLimitDecision(
        boolean allowed,
        long remainingTokens,
        Duration retryAfter
) {

    public long retryAfterSeconds() {
        if (retryAfter == null || retryAfter.isZero() || retryAfter.isNegative()) {
            return 0;
        }

        long nanos = retryAfter.toNanos();
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }
}
