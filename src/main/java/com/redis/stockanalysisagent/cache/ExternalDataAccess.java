package com.redis.stockanalysisagent.cache;

public record ExternalDataAccess(
        String cacheName,
        String key,
        String source,
        long durationMs
) {

    public static final String SOURCE_API = "api";
    public static final String SOURCE_CACHE = "cache";

    public ExternalDataAccess {
        cacheName = cacheName == null ? "" : cacheName.trim();
        key = key == null ? "" : key.trim();
        source = SOURCE_CACHE.equals(source) ? SOURCE_CACHE : SOURCE_API;
        durationMs = Math.max(0, durationMs);
    }
}
