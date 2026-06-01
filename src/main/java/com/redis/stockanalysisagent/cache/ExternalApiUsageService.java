package com.redis.stockanalysisagent.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public class ExternalApiUsageService {

    private static final String KEY_PREFIX = "stock-analysis:external-api:hits:";
    private static final String SEC = "sec";
    private static final String TAVILY = "tavily";
    private static final String TWELVE_DATA = "twelve-data";

    private final StringRedisTemplate redisTemplate;

    public ExternalApiUsageService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void recordHitForCacheName(String cacheName) {
        providerIdForCacheName(cacheName).ifPresent(providerId ->
                redisTemplate.opsForValue().increment(redisKey(providerId)));
    }

    public ExternalApiUsageSnapshot snapshot() {
        return new ExternalApiUsageSnapshot(
                count(SEC),
                count(TAVILY),
                count(TWELVE_DATA)
        );
    }

    public ExternalApiUsageSnapshot reset(String providerId) {
        String normalizedProviderId = normalizeProviderId(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider."));
        redisTemplate.delete(redisKey(normalizedProviderId));
        return snapshot();
    }

    private Optional<String> providerIdForCacheName(String cacheName) {
        if (cacheName == null) {
            return Optional.empty();
        }

        return switch (cacheName) {
            case CacheNames.SEC_TICKER_INDEX, CacheNames.SEC_COMPANY_FACTS, CacheNames.SEC_SUBMISSIONS ->
                    Optional.of(SEC);
            case CacheNames.TAVILY_NEWS_SEARCH -> Optional.of(TAVILY);
            case CacheNames.MARKET_DATA_QUOTES, CacheNames.TECHNICAL_ANALYSIS_SNAPSHOTS,
                 CacheNames.HISTORICAL_CANDLES -> Optional.of(TWELVE_DATA);
            default -> Optional.empty();
        };
    }

    private Optional<String> normalizeProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }

        return switch (providerId.trim().toLowerCase(Locale.ROOT)) {
            case "sec", "sec-api" -> Optional.of(SEC);
            case "tavily", "taviliy", "tavily-api", "taviliy-api" -> Optional.of(TAVILY);
            case "twelve-data", "twelvedata", "twelve-data-api" -> Optional.of(TWELVE_DATA);
            default -> Optional.empty();
        };
    }

    private long count(String providerId) {
        String value = redisTemplate.opsForValue().get(redisKey(providerId));
        if (value == null || value.isBlank()) {
            return 0;
        }

        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String redisKey(String providerId) {
        return KEY_PREFIX + providerId;
    }
}
