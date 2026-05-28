# Caching With Redis

This codebase has two cache layers.

1. External data caching stores deterministic API responses in Redis with short TTLs.
2. Semantic response caching stores final assistant answers and retrieves them by exact or semantic similarity.

Both layers are controlled by the user session. The HTTP session stores whether API caching and semantic caching are enabled for the current user.

Redis 8 is enough for the local Redis cache. Redis Stack is not required. LangCache is separate from the local Spring Redis cache.

## Reference Implementation

1. `src/main/java/com/redis/stockanalysisagent/cache/CacheConfig.java`
2. `src/main/java/com/redis/stockanalysisagent/cache/CacheNames.java`
3. `src/main/java/com/redis/stockanalysisagent/cache/ExternalDataCache.java`
4. `src/main/java/com/redis/stockanalysisagent/cache/CacheInspectionService.java`
5. `src/main/java/com/redis/stockanalysisagent/cache/controller/ChatCacheController.java`
6. `src/main/java/com/redis/stockanalysisagent/semanticcache/SemanticCacheAdvisor.java`
7. `src/main/java/com/redis/stockanalysisagent/semanticcache/SemanticCacheStoreAdvisor.java`
8. `src/main/java/com/redis/stockanalysisagent/semanticcache/LangCacheSemanticAnalysisCache.java`
9. `src/main/java/com/redis/stockanalysisagent/chat/ChatService.java`
10. `src/main/java/com/redis/stockanalysisagent/agent/coordinator/CoordinatorAgent.java`

## External Data Cache Model

Spring Cache writes Redis keys in this shape.

```text
{cacheName}::{key}
```

Examples from this codebase.

```text
market-data-quotes::AAPL
technical-analysis-snapshots::AAPL|1day|60|20|20|14
sec-ticker-index::all
sec-company-facts::0000320193
sec-submissions::0000320193
tavily-news-search::AAPL|Apple Inc.|latest earnings|5
```

Cache names and TTLs are configured centrally.

```java
RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
        .disableCachingNullValues()
        .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
        .entryTtl(Duration.ofMinutes(10));

return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaults)
        .withInitialCacheConfigurations(Map.of(
                CacheNames.MARKET_DATA_QUOTES, defaults.entryTtl(Duration.ofSeconds(30)),
                CacheNames.TECHNICAL_ANALYSIS_SNAPSHOTS, defaults.entryTtl(Duration.ofMinutes(2)),
                CacheNames.SEC_TICKER_INDEX, defaults.entryTtl(Duration.ofHours(24)),
                CacheNames.SEC_COMPANY_FACTS, defaults.entryTtl(Duration.ofHours(12)),
                CacheNames.SEC_SUBMISSIONS, defaults.entryTtl(Duration.ofMinutes(15)),
                CacheNames.TAVILY_NEWS_SEARCH, defaults.entryTtl(Duration.ofHours(24))
        ))
        .build();
```

Choose TTLs from the freshness requirements of the upstream data. Fast changing market quotes get seconds. SEC company metadata can live for hours.

## External Data Cache Flow

1. A provider asks `ExternalDataCache` for a value.
2. The cache checks the request scoped caching flag.
3. If caching is disabled, the loader runs and the value is not stored.
4. If Redis has a value, the cached value is returned.
5. If Redis misses, the cache locks on `cacheName::key`, checks Redis again, runs the loader, and stores the loaded value.
6. Each access records whether the value came from Redis or from the API.

The wrapper keeps provider code small.

```java
Object cachedPayload = externalDataCache.getOrLoad(
        CacheNames.MARKET_DATA_QUOTES,
        ticker.toUpperCase(),
        () -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/quote")
                        .queryParam("symbol", ticker.toUpperCase())
                        .queryParam("apikey", properties.getApiKey())
                        .build())
                .retrieve()
                .body(JsonNode.class)
);
```

## Implement External Data Caching

### 1. Define cache names

Use stable names that describe the data source and payload type.

```java
public final class CacheNames {
    public static final String MARKET_DATA_QUOTES = "market-data-quotes";
    public static final String SEC_SUBMISSIONS = "sec-submissions";

    private CacheNames() {
    }
}
```

### 2. Configure Redis caching

Use a Redis cache manager, JSON values, string keys, and per cache TTLs.

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        GenericJacksonJsonRedisSerializer valueSerializer =
                new GenericJacksonJsonRedisSerializer(JsonMapper.builder().findAndAddModules().build());

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
                .entryTtl(Duration.ofMinutes(10));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .build();
    }
}
```

### 3. Put cache access behind one wrapper

The repository uses a wrapper instead of direct cache calls in every provider. The wrapper handles bypass, locking, metrics, and progress events.

```java
public <T> T getOrLoad(String cacheName, String key, Supplier<T> loader) {
    if (!Boolean.TRUE.equals(cachingEnabled.get())) {
        return loader.get();
    }

    Cache cache = cacheManager.getCache(cacheName);
    if (cache == null) {
        return loader.get();
    }

    Cache.ValueWrapper cached = cache.get(key);
    if (cached != null && cached.get() != null) {
        return (T) cached.get();
    }

    String lockKey = cacheName + "::" + key;
    Object lock = locks.computeIfAbsent(lockKey, ignored -> new Object());
    synchronized (lock) {
        Cache.ValueWrapper doubleChecked = cache.get(key);
        if (doubleChecked != null && doubleChecked.get() != null) {
            return (T) doubleChecked.get();
        }

        T loaded = loader.get();
        if (loaded != null) {
            cache.put(key, loaded);
        }
        return loaded;
    }
}
```

### 4. Add a request scoped bypass

Store the user preference in the HTTP session. At request time, set a `ThreadLocal` flag before the analysis starts and clear it in `finally`.

```java
externalDataCache.setCachingEnabled(apiCachingEnabled);
try {
    analysisTurn = chatAnalysisService.analyze(...);
} finally {
    externalDataCache.clearCachingEnabled();
}
```

This keeps the preference out of provider signatures.

### 5. Add inspection and delete endpoints

Expose cache contents for debugging and let users delete individual entries.

```java
byte[] redisKey = (cacheName + "::" + normalizedKey).getBytes(StandardCharsets.UTF_8);
Long deleted = connection.keyCommands().del(redisKey);
```

Guard these endpoints with the same session checks as the rest of the app.

The inspection API scans known external cache names and returns up to 50 entries per cache. It reports TTL, value type, byte size, truncation state, and a value preview.

## Semantic Cache Model

The semantic cache uses the normalized user request as the cache key.

```java
private String semanticCacheKey(String userId, String message) {
    return "user=%s\nmessage=%s".formatted(userId, message);
}
```

The implementation searches Redis LangCache with exact and semantic strategies.

```java
LangCacheApiModels.SearchRequest searchRequest = new LangCacheApiModels.SearchRequest(
        request,
        similarityThreshold,
        searchStrategies,
        attributes
);
```

Stored entries use a TTL from configuration.

```yaml
stock-analysis:
  semantic-cache:
    ttl-seconds: 300
    lang-cache:
      url: ${LANGCACHE_URL}
      cache-id: ${LANGCACHE_CACHE_ID}
      api-key: ${LANGCACHE_API_KEY}
      similarity-threshold: 0.9
      exact-search: true
      semantic-search: true
```

## Semantic Cache Flow

1. The chat service builds a stable key from `userId` and message.
2. The coordinator adds the key to the Spring AI advisor context when semantic caching is enabled.
3. `SemanticCacheAdvisor` searches the semantic cache before the LLM call.
4. A hit returns a normal chat response with metadata that marks the response as cached.
5. A miss continues to the coordinator LLM and specialist agents.
6. After a successful agent backed answer, the coordinator stores the final answer in the semantic cache.
7. Guardrail responses, cache hits, and empty responses are not stored.

## Implement Semantic Caching

### 1. Create a cache interface

```java
public interface SemanticAnalysisCache {
    Optional<String> findCachedResponse(String request);
    void storeFinalResponse(String request, String response);
}
```

### 2. Search before expensive work

```java
Optional<String> cachedResponse = semanticCache.findCachedResponse(cacheKey);
if (cachedResponse.isPresent()) {
    return asCacheHitResponse(cacheKey, cachedResponse.get(), durationMs);
}
```

Return the cached answer in the same response type as a normal model call. The rest of the pipeline can treat cache hits as normal completions.

### 3. Store only final answers

```java
if (semanticCachingEnabled && !cacheHit && !guardrailHit && finalResponse != null) {
    semanticAnalysisCache.storeFinalResponse(semanticCacheKey.trim(), finalResponse.trim());
}
```

Store final user visible answers. Avoid storing intermediate tool outputs, blocked responses, and failed responses.

### 4. Keep cache metadata visible

Return metadata such as `semantic_cache_hit`, `semantic_cache_key`, and `semantic_cache_duration_ms`. The UI and tests can prove whether Redis or the model produced the response.

### 5. Let TTL handle semantic cache expiration

This repository does not expose a semantic cache delete endpoint. LangCache expiration is controlled by `ttl-seconds`. Disabling semantic caching for a request bypasses lookup and store, and existing entries remain until expiration.

## Tests

Write tests for these behaviors.

1. Cache miss calls the loader, stores the value, and records an API access.
2. Cache hit returns the stored value and records a cache access.
3. Disabled API caching runs the loader and bypasses the stored value.
4. A missing cache manager falls back to the loader.
5. Per key locking prevents duplicate API calls under concurrent misses.
6. Cache inspection lists only known cache names and includes TTL.
7. Deleting a cache entry deletes `{cacheName}::{key}`.
8. Semantic cache hit skips the model call.
9. Semantic cache miss stores the final answer after a successful run.
10. Semantic cache bypass skips lookup and store for that request.
