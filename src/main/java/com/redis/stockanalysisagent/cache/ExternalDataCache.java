package com.redis.stockanalysisagent.cache;

import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.reliability.CircuitBreakerOpenException;
import com.redis.stockanalysisagent.reliability.CircuitBreakerService;
import com.redis.stockanalysisagent.reliability.ProviderCapacityService;
import com.redis.stockanalysisagent.reliability.ProviderCapacityTimeoutException;
import com.redis.stockanalysisagent.reliability.ProviderDeadLetterService;
import com.redis.stockanalysisagent.reliability.ProviderLatencySimulationService;
import com.redis.stockanalysisagent.reliability.ProviderRetriesExhaustedException;
import com.redis.stockanalysisagent.reliability.ProviderRetryProperties;
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
    private final ProviderCapacityService providerCapacityService;
    private final ProviderLatencySimulationService latencySimulationService;
    private final ProviderRetryProperties retryProperties;
    private final ProviderDeadLetterService deadLetterService;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ThreadLocal<List<ExternalDataAccess>> recordedAccesses = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<Boolean> cachingEnabled = ThreadLocal.withInitial(() -> true);

    @Autowired
    public ExternalDataCache(
            CacheManager cacheManager,
            ChatProgressPublisher progressPublisher,
            ExternalApiUsageService apiUsageService,
            CircuitBreakerService circuitBreakerService,
            ProviderCapacityService providerCapacityService,
            ProviderLatencySimulationService latencySimulationService,
            ProviderRetryProperties retryProperties,
            ProviderDeadLetterService deadLetterService
    ) {
        this.cacheManager = cacheManager;
        this.progressPublisher = progressPublisher;
        this.apiUsageService = apiUsageService;
        this.circuitBreakerService = circuitBreakerService;
        this.providerCapacityService = providerCapacityService;
        this.latencySimulationService = latencySimulationService;
        this.retryProperties = retryProperties;
        this.deadLetterService = deadLetterService;
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
        this.providerCapacityService = null;
        this.latencySimulationService = null;
        this.retryProperties = null;
        this.deadLetterService = null;
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
        try {
            T loaded = loadWithProviderControls(cacheName, key, () -> {
                progressPublisher.running(
                        id,
                        label,
                        ChatProgressPublisher.KIND_SYSTEM,
                        "Fetching " + dataProgressName(cacheName, key) + "."
                );
                apiUsageService.recordHitForCacheName(cacheName);
                sleepIfLatencySimulationEnabled(cacheName);
                T result = loader.get();
                progressPublisher.completed(
                        id,
                        label,
                        ChatProgressPublisher.KIND_SYSTEM,
                        elapsedDurationMs(startedAt),
                        "Fetched " + dataProgressName(cacheName, key) + "."
                );
                return result;
            });
            return loaded;
        } catch (CircuitBreakerOpenException ex) {
            appendDeadLetter(id, cacheName, key, 0, ex);
            progressPublisher.failed(
                    id,
                    "Circuit breaker",
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    "Blocked " + providerLabel(ex.providerId()) + " call because provider state is " + ex.state().state() + "."
            );
            throw ex;
        } catch (ProviderCapacityTimeoutException ex) {
            appendDeadLetter(id, cacheName, key, 0, ex);
            throw ex;
        } catch (ProviderRetriesExhaustedException ex) {
            appendDeadLetter(id, cacheName, key, ex.attempts(), ex);
            progressPublisher.failed(
                    id,
                    label,
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    errorMessage(ex)
            );
            throw ex;
        } catch (RuntimeException ex) {
            appendDeadLetter(id, cacheName, key, 1, ex);
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

    private <T> T loadWithProviderControls(String cacheName, String key, Supplier<T> loader) {
        String providerId = providerId(cacheName);
        if (providerId == null) {
            return loader.get();
        }

        if (providerCapacityService == null || !providerCapacityService.enabled()) {
            return loadWithCircuitBreaker(providerId, () -> loadWithRetries(providerId, cacheName, key, loader));
        }

        String label = "Waiting for " + providerLabel(providerId) + " capacity";
        String stepId = capacityStepId(providerId, cacheName, key);
        long startedAt = System.nanoTime();
        progressPublisher.running(
                stepId,
                label,
                ChatProgressPublisher.KIND_SYSTEM,
                "Waiting for a Redis semaphore permit before calling " + providerLabel(providerId) + "."
        );
        try (ProviderCapacityService.Permit permit = providerCapacityService.acquire(providerId)) {
            long waitedMs = elapsedDurationMs(startedAt);
            progressPublisher.completed(
                    stepId,
                    providerLabel(providerId) + " permit acquired",
                    ChatProgressPublisher.KIND_SYSTEM,
                    waitedMs,
                    capacityAcquiredSummary(providerId, permit, waitedMs)
            );
            return loadWithCircuitBreaker(providerId, () -> loadWithRetries(providerId, cacheName, key, loader));
        } catch (ProviderCapacityTimeoutException ex) {
            progressPublisher.failed(
                    stepId,
                    label,
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    "Timed out waiting for " + providerLabel(providerId) + " capacity."
            );
            throw ex;
        }
    }

    private String capacityAcquiredSummary(String providerId, ProviderCapacityService.Permit permit, long waitedMs) {
        String wait = waitedMs < 100 ? "immediately" : "after " + durationText(waitedMs);
        return "Acquired " + providerLabel(providerId) + " permit " + wait
                + " (" + permit.activeCount() + "/" + permit.limit() + " active).";
    }

    private String durationText(long durationMs) {
        if (durationMs >= 1_000 && durationMs % 1_000 == 0) {
            return (durationMs / 1_000) + "s";
        }
        if (durationMs >= 1_000) {
            return (durationMs / 1_000) + "." + ((durationMs % 1_000) / 100) + "s";
        }
        return durationMs + "ms";
    }

    private <T> T loadWithCircuitBreaker(String providerId, Supplier<T> loader) {
        if (circuitBreakerService == null) {
            return loader.get();
        }

        return circuitBreakerService.call(providerId, loader);
    }

    private <T> T loadWithRetries(String providerId, String cacheName, String key, Supplier<T> loader) {
        int maxAttempts = retryProperties == null || !retryProperties.isEnabled()
                ? 1
                : retryProperties.getMaxAttempts();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return loader.get();
            } catch (CircuitBreakerOpenException | ProviderCapacityTimeoutException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt >= maxAttempts) {
                    throw new ProviderRetriesExhaustedException(providerId, attempt, ex);
                }
                progressPublisher.running(
                        retryStepId(providerId),
                        "Retrying " + providerLabel(providerId),
                        ChatProgressPublisher.KIND_SYSTEM,
                        "Attempt " + attempt + " failed. Retrying " + providerLabel(providerId) + "."
                );
                sleepBeforeRetry();
            }
        }
        throw new ProviderRetriesExhaustedException(providerId, maxAttempts, lastFailure);
    }

    private void sleepBeforeRetry() {
        if (retryProperties == null || retryProperties.getBackoff().isZero() || retryProperties.getBackoff().isNegative()) {
            return;
        }
        try {
            Thread.sleep(retryProperties.getBackoff().toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry provider call.", ex);
        }
    }

    private void appendDeadLetter(String stepId, String cacheName, String key, int attempts, RuntimeException ex) {
        if (deadLetterService == null) {
            return;
        }
        String providerId = providerId(cacheName);
        if (providerId == null) {
            return;
        }
        deadLetterService.append(stepId, providerId, providerLabel(providerId), cacheName, key, attempts, ex);
    }

    private void sleepIfLatencySimulationEnabled(String cacheName) {
        if (latencySimulationService == null) {
            return;
        }
        String providerId = providerId(cacheName);
        if (providerId != null) {
            latencySimulationService.sleepIfConfigured(providerId);
        }
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
        return "DATA:%s:%s".formatted(source, safeIdPart(providerId(cacheName)));
    }

    private String capacityStepId(String providerId, String cacheName, String key) {
        return "CAPACITY:%s".formatted(safeIdPart(providerId));
    }

    private String retryStepId(String providerId) {
        return "RETRY:%s".formatted(safeIdPart(providerId));
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
