package com.redis.stockanalysisagent.cache;

public record ExternalApiUsageSnapshot(
        long secApiHits,
        long tavilyApiHits,
        long twelveDataApiHits
) {

    public static ExternalApiUsageSnapshot empty() {
        return new ExternalApiUsageSnapshot(0, 0, 0);
    }
}
