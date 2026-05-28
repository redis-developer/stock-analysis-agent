# Caching Slide Structure

## Deck Goal

Explain how the application uses Redis for external API data caching and LangCache for semantic response caching.

## Audience

Backend engineers adding caching to applications that call third party APIs and LLMs.

## Slide 1: Title

Title: Caching With Redis

Talking points:

1. External API data is cached in Redis with TTLs.
2. Final assistant answers are cached with semantic lookup.
3. Session preferences control both cache layers.

Visual:

1. User request.
2. Application.
3. Redis data cache.
4. LangCache semantic cache.
5. External APIs and LLM.

Repo anchors:

1. `docs/caching.md`
2. `src/main/java/com/redis/stockanalysisagent/cache/ExternalDataCache.java`

## Slide 2: Two Cache Layers

Title: Cache Responsibilities

Talking points:

1. The external data cache stores deterministic provider responses.
2. The semantic cache stores final assistant answers.
3. External cache keys use provider specific identifiers.
4. Semantic cache keys use `userId` and normalized user message.

Visual:

1. Two lanes.
2. External data lane.
3. Semantic response lane.

## Slide 3: External Cache Key Model

Title: Redis Key Shape

Talking points:

1. Spring Cache writes keys as `{cacheName}::{key}`.
2. Cache names describe the source and payload type.
3. Provider code chooses the key.
4. Redis values use JSON serialization.

Slide content:

```text
market-data-quotes::AAPL
technical-analysis-snapshots::AAPL|1day|60|20|20|14
sec-submissions::0000320193
tavily-news-search::AAPL|Apple Inc.|latest earnings|5
```

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/cache/CacheNames.java`
2. `src/main/java/com/redis/stockanalysisagent/cache/CacheConfig.java`

## Slide 4: TTL Strategy

Title: TTLs Match Data Freshness

Talking points:

1. Market quotes use a 30 second TTL.
2. Technical snapshots use a 2 minute TTL.
3. SEC ticker index uses a 24 hour TTL.
4. SEC company facts use a 12 hour TTL.
5. SEC submissions use a 15 minute TTL.
6. Tavily news search uses a 24 hour TTL.

Visual:

1. Table with cache name and TTL.

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/cache/CacheConfig.java`

## Slide 5: External Cache Flow

Title: Cache Hit And Miss Flow

Talking points:

1. Provider calls `ExternalDataCache.getOrLoad`.
2. Request scoped flag decides whether caching is enabled.
3. Cache hit returns the Redis value.
4. Cache miss runs the loader and stores a non null value.
5. A local per key lock reduces duplicate loads in one app process.
6. Each access records source and duration.

Visual:

1. Decision flow with cache bypass, hit, miss, loader, store.

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/cache/ExternalDataCache.java`

## Slide 6: Provider Integration

Title: Keep Provider Code Small

Talking points:

1. Provider code supplies the cache name, key, and loader.
2. The wrapper owns bypass, locking, recording, and progress events.
3. Provider code normalizes cached payloads back into domain types.

Code callout:

```java
externalDataCache.getOrLoad(
        CacheNames.MARKET_DATA_QUOTES,
        ticker.toUpperCase(),
        () -> loadQuoteFromProvider(ticker)
);
```

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/providers/twelvedata/TwelveDataMarketDataProvider.java`
2. `src/main/java/com/redis/stockanalysisagent/providers/sec/SecNewsProvider.java`

## Slide 7: Cache Toggle

Title: Request Scoped Bypass

Talking points:

1. The HTTP session stores `apiCachingEnabled`.
2. The chat controller resolves the active value for each request.
3. The chat service sets the cache flag before analysis.
4. The flag is cleared in `finally`.
5. Disabled caching bypasses reads and writes.

Code callout:

```java
externalDataCache.setCachingEnabled(apiCachingEnabled);
try {
    analysisTurn = chatAnalysisService.analyze(...);
} finally {
    externalDataCache.clearCachingEnabled();
}
```

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/chat/ChatController.java`
2. `src/main/java/com/redis/stockanalysisagent/chat/ChatService.java`

## Slide 8: Cache Inspection

Title: Inspect And Delete Entries

Talking points:

1. `GET /api/chat/cache` scans known external cache names.
2. The response includes TTL, value type, size, truncation state, and value preview.
3. `DELETE /api/chat/cache/{cacheName}/entries?key={key}` deletes one entry.
4. Cache names are checked against the allowlist.

Visual:

1. Cache table with cache name, key, TTL, and value preview.

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/cache/CacheInspectionService.java`
2. `src/main/java/com/redis/stockanalysisagent/cache/controller/ChatCacheController.java`

## Slide 9: Semantic Cache Flow

Title: Reusing Final Answers

Talking points:

1. The chat service builds `user={userId}\nmessage={message}`.
2. `SemanticCacheAdvisor` searches LangCache before the LLM call.
3. A hit returns a synthetic coordinator response.
4. A miss continues through guardrail, memory, coordinator, and specialist tools.
5. The coordinator stores final answers after successful specialist work.

Visual:

1. Request.
2. Semantic lookup.
3. Hit response path.
4. Miss execution path.
5. Store final answer.

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/semanticcache/SemanticCacheAdvisor.java`
2. `src/main/java/com/redis/stockanalysisagent/agent/coordinator/CoordinatorAgent.java`

## Slide 10: Semantic Cache Configuration

Title: LangCache Settings

Talking points:

1. TTL defaults to 300 seconds.
2. Similarity threshold defaults to `0.9`.
3. Exact search and semantic search are enabled by default.
4. Credentials should come from environment variables.
5. The app has no local delete endpoint for semantic cache entries.

Config callout:

```yaml
stock-analysis:
  semantic-cache:
    ttl-seconds: 300
    lang-cache:
      url: ${LANGCACHE_URL}
      cache-id: ${LANGCACHE_CACHE_ID}
      api-key: ${LANGCACHE_API_KEY}
```

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/semanticcache/SemanticCacheProperties.java`
2. `src/main/java/com/redis/stockanalysisagent/semanticcache/LangCacheSemanticAnalysisCache.java`

## Slide 11: Implementation Checklist

Title: Build Steps

Talking points:

1. Define cache names.
2. Configure Redis cache manager and TTLs.
3. Wrap cache reads in one `getOrLoad` component.
4. Add request scoped cache bypass.
5. Add inspection and delete endpoints for external cache entries.
6. Add semantic cache lookup before the LLM call.
7. Store only final user visible answers.
8. Return metadata that identifies cache hits.
9. Test hit, miss, bypass, delete, and semantic reuse behavior.

Visual:

1. Checklist with nine rows.

