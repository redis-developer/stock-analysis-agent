package com.redis.stockanalysisagent.cache;

import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.reliability.CircuitBreakerOpenException;
import com.redis.stockanalysisagent.reliability.CircuitBreakerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Component
public class ExternalDataCache {

    private final CacheManager cacheManager;
    private final ChatProgressPublisher progressPublisher;
    private final ExternalApiUsageService apiUsageService;
    private final CircuitBreakerService circuitBreakerService;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ThreadLocal<List<ExternalDataAccess>> recordedAccesses = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<Boolean> cachingEnabled = ThreadLocal.withInitial(() -> true);

    @Autowired
    public ExternalDataCache(
            CacheManager cacheManager,
            ChatProgressPublisher progressPublisher,
            ExternalApiUsageService apiUsageService,
            CircuitBreakerService circuitBreakerService
    ) {
        this.cacheManager = cacheManager;
        this.progressPublisher = progressPublisher;
        this.apiUsageService = apiUsageService;
        this.circuitBreakerService = circuitBreakerService;
    }

    ExternalDataCache(
            CacheManager cacheManager,
            ChatProgressPublisher progressPublisher,
            ExternalApiUsageService apiUsageService
    ) {
        this.cacheManager = cacheManager;
        this.progressPublisher = progressPublisher;
        this.apiUsageService = apiUsageService;
        this.circuitBreakerService = null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheName, String key, Supplier<T> loader) {
        long startedAt = System.nanoTime();
        if (!Boolean.TRUE.equals(cachingEnabled.get())) {
            T loaded = loadFromApi(cacheName, key, loader);
            recordAccess(cacheName, key, ExternalDataAccess.SOURCE_API, startedAt);
            return loaded;
        }

        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            T loaded = loadFromApi(cacheName, key, loader);
            recordAccess(cacheName, key, ExternalDataAccess.SOURCE_API, startedAt);
            return loaded;
        }

        Cache.ValueWrapper cached = cache.get(key);
        if (cached != null && cached.get() != null) {
            recordCacheHitProgress(cacheName, key, startedAt);
            recordAccess(cacheName, key, ExternalDataAccess.SOURCE_CACHE, startedAt);
            return (T) cached.get();
        }

        String lockKey = cacheName + "::" + key;
        Object lock = locks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            try {
                Cache.ValueWrapper doubleChecked = cache.get(key);
                if (doubleChecked != null && doubleChecked.get() != null) {
                    recordCacheHitProgress(cacheName, key, startedAt);
                    recordAccess(cacheName, key, ExternalDataAccess.SOURCE_CACHE, startedAt);
                    return (T) doubleChecked.get();
                }

                T loaded = loadFromApi(cacheName, key, loader);
                if (loaded != null) {
                    cache.put(key, loaded);
                }
                recordAccess(cacheName, key, ExternalDataAccess.SOURCE_API, startedAt);
                return loaded;
            } finally {
                locks.remove(lockKey, lock);
            }
        }
    }

    public void setCachingEnabled(boolean enabled) {
        cachingEnabled.set(enabled);
    }

    public void clearCachingEnabled() {
        cachingEnabled.remove();
    }

    public void clearRecordedAccesses() {
        recordedAccesses.remove();
    }

    public List<ExternalDataAccess> drainRecordedAccesses() {
        List<ExternalDataAccess> accesses = List.copyOf(recordedAccesses.get());
        recordedAccesses.remove();
        return accesses;
    }

    private void recordAccess(String cacheName, String key, String source, long startedAt) {
        recordedAccesses.get().add(new ExternalDataAccess(
                cacheName,
                key,
                source,
                elapsedDurationMs(startedAt)
        ));
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private <T> T loadFromApi(String cacheName, String key, Supplier<T> loader) {
        long startedAt = System.nanoTime();
        String id = dataAccessStepId(cacheName, key, ExternalDataAccess.SOURCE_API);
        String label = apiProgressLabel(cacheName);
        progressPublisher.running(
                id,
                label,
                ChatProgressPublisher.KIND_SYSTEM,
                "Fetching " + dataProgressName(cacheName, key) + "."
        );
        try {
            T loaded = loadWithCircuitBreaker(cacheName, () -> {
                apiUsageService.recordHitForCacheName(cacheName);
                return loader.get();
            });
            progressPublisher.completed(
                    id,
                    label,
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    "Fetched " + dataProgressName(cacheName, key) + "."
            );
            return loaded;
        } catch (CircuitBreakerOpenException ex) {
            progressPublisher.failed(
                    id,
                    "Circuit breaker",
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    "Blocked " + providerLabel(ex.providerId()) + " call because provider state is " + ex.state().state() + "."
            );
            throw ex;
        } catch (RuntimeException ex) {
            progressPublisher.failed(
                    id,
                    label,
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    errorMessage(ex)
            );
            throw ex;
        }
    }

    private <T> T loadWithCircuitBreaker(String cacheName, Supplier<T> loader) {
        if (circuitBreakerService == null) {
            return loader.get();
        }

        String providerId = providerId(cacheName);
        if (providerId == null) {
            return loader.get();
        }

        return circuitBreakerService.call(providerId, loader);
    }

    private void recordCacheHitProgress(String cacheName, String key, long startedAt) {
        String id = dataAccessStepId(cacheName, key, ExternalDataAccess.SOURCE_CACHE);
        progressPublisher.completed(
                id,
                "Reading cached data",
                ChatProgressPublisher.KIND_SYSTEM,
                elapsedDurationMs(startedAt),
                "Read " + dataProgressName(cacheName, key) + " from Redis."
        );
    }

    private String dataAccessStepId(String cacheName, String key, String source) {
        return "DATA:%s:%s:%s".formatted(
                source,
                safeIdPart(cacheName),
                safeIdPart(key)
        );
    }

    private String safeIdPart(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.trim().replaceAll("[^A-Za-z0-9]+", "_");
    }

    private String apiProgressLabel(String cacheName) {
        return switch (cacheName) {
            case CacheNames.MARKET_DATA_QUOTES, CacheNames.TECHNICAL_ANALYSIS_SNAPSHOTS -> "Calling Twelve Data API";
            case CacheNames.SEC_TICKER_INDEX, CacheNames.SEC_COMPANY_FACTS, CacheNames.SEC_SUBMISSIONS -> "Calling SEC API";
            case CacheNames.TAVILY_NEWS_SEARCH -> "Calling Tavily API";
            default -> "Calling third party API";
        };
    }

    private String providerId(String cacheName) {
        if (cacheName == null) {
            return null;
        }

        return switch (cacheName) {
            case CacheNames.MARKET_DATA_QUOTES, CacheNames.TECHNICAL_ANALYSIS_SNAPSHOTS,
                 CacheNames.HISTORICAL_CANDLES -> "twelve-data";
            case CacheNames.SEC_TICKER_INDEX, CacheNames.SEC_COMPANY_FACTS, CacheNames.SEC_SUBMISSIONS -> "sec";
            case CacheNames.TAVILY_NEWS_SEARCH -> "tavily";
            default -> null;
        };
    }

    private String providerLabel(String providerId) {
        return switch (providerId) {
            case "twelve-data" -> "Twelve Data";
            case "sec" -> "SEC";
            case "tavily" -> "Tavily";
            default -> providerId;
        };
    }

    private String dataProgressName(String cacheName, String key) {
        if (key == null || key.isBlank()) {
            return cacheName == null || cacheName.isBlank() ? "external data" : cacheName;
        }

        return "%s for %s".formatted(cacheName, key);
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
