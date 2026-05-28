# Rate Limiter With Redis

Use a Redis backed token bucket per authenticated user. The bucket state lives in Redis, so every application instance enforces the same limit.

This repository uses Bucket4j with Redis Lettuce. The same model works in other frameworks if request filtering, key resolution, and Redis bucket state stay separate.

Redis 8 is enough for the rate limiter. Redis Stack is not required.

## Reference Implementation

1. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingProperties.java`
2. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingConfig.java`
3. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RedisBucketRateLimitService.java`
4. `src/main/java/com/redis/stockanalysisagent/ratelimiting/HttpSessionRateLimitKeyResolver.java`
5. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingFilter.java`
6. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitStatusProvider.java`
7. `src/main/java/com/redis/stockanalysisagent/session/ChatSessionAccess.java`
8. `src/main/resources/application.yaml`

## Behavior

The app protects only chat execution requests.

```yaml
app:
  rate-limiting:
    enabled: true
    requests-per-minute: 6
    refill-period: 1m
    redis-key-prefix: stock-analysis:rate-limit:user
    protected-paths:
      - /api/chat
      - /api/chat/stream
```

Only `POST` requests to configured protected paths consume a token.

The key shape is user scoped.

```text
stock-analysis:rate-limit:user:{userId}
```

The filter skips rate limiting when there is no logged in user or when the user has disabled rate limiting in the session.

This is a token bucket. Buckets start full, each protected request consumes one token, and Bucket4j refills the bucket with `requestsPerMinute` tokens every `refillPeriod`. The Redis value is Bucket4j opaque bucket state.

## Flow

1. The request enters `RateLimitingFilter`.
2. The filter checks the HTTP method and configured protected path list.
3. `HttpSessionRateLimitKeyResolver` reads the current user id from the HTTP session.
4. The resolver returns `stock-analysis:rate-limit:user:{userId}` when rate limiting is enabled for that session.
5. `RedisBucketRateLimitService` consumes one token from the Redis backed bucket.
6. Allowed requests continue through the filter chain.
7. Rejected requests return `429` with `Retry-After` and rate limit headers.

## Implementation Steps

### 1. Add dependencies

```kotlin
implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.19.0")
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

Use the equivalent Redis client and token bucket package in other stacks.

### 2. Define configuration properties

Keep all rate limit settings in configuration.

```java
@ConfigurationProperties(prefix = "app.rate-limiting")
public class RateLimitingProperties {

    private boolean enabled = true;
    private int requestsPerMinute = 6;
    private Duration refillPeriod = Duration.ofMinutes(1);
    private String redisKeyPrefix = "stock-analysis:rate-limit:user";
    private List<String> protectedPaths = List.of("/api/chat", "/api/chat/stream");

    public int requireRequestsPerMinute() {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("Rate limit requests per minute must be greater than zero.");
        }
        return requestsPerMinute;
    }

    public Duration requireRefillPeriod() {
        if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Rate limit refill period must be positive.");
        }
        return refillPeriod;
    }
}
```

Validate these values at startup or when the service is constructed.

### 3. Connect Bucket4j to Redis

Use the same Redis host, port, username, and password as the application.

```java
@Bean(destroyMethod = "shutdown")
public RedisClient rateLimitingRedisClient(
        @Value("${spring.data.redis.host:localhost}") String host,
        @Value("${spring.data.redis.port:6379}") int port,
        @Value("${spring.data.redis.username:}") String username,
        @Value("${spring.data.redis.password:}") String password
) {
    RedisURI.Builder builder = RedisURI.builder()
            .withHost(host)
            .withPort(port);

    if (StringUtils.hasText(username)) {
        builder.withAuthentication(username, password.toCharArray());
    } else if (StringUtils.hasText(password)) {
        builder.withPassword(password.toCharArray());
    }

    return RedisClient.create(builder.build());
}
```

Create a Bucket4j `ProxyManager`.

```java
@Bean
public ProxyManager<String> rateLimitingProxyManager(
        StatefulRedisConnection<String, byte[]> connection,
        RateLimitingProperties properties
) {
    return Bucket4jLettuce.casBasedBuilder(connection)
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    properties.requireRefillPeriod()
            ))
            .build();
}
```

The expiration strategy removes idle bucket keys after the bucket can refill to max.

### 4. Implement the bucket service

Configure one interval refill bucket per key.

```java
public class RedisBucketRateLimitService implements RateLimitService {

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration bucketConfiguration;
    private final int requestsPerMinute;

    public RedisBucketRateLimitService(ProxyManager<String> proxyManager, RateLimitingProperties properties) {
        this.proxyManager = proxyManager;
        this.requestsPerMinute = properties.requireRequestsPerMinute();
        Duration refillPeriod = properties.requireRefillPeriod();
        this.bucketConfiguration = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute).refillIntervally(requestsPerMinute, refillPeriod))
                .build();
    }

    public RateLimitDecision consume(String key) {
        Bucket bucket = proxyManager.getProxy(key, () -> bucketConfiguration);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return new RateLimitDecision(
                probe.isConsumed(),
                probe.getRemainingTokens(),
                probe.isConsumed() ? Duration.ZERO : Duration.ofNanos(probe.getNanosToWaitForRefill())
        );
    }
}
```

Return a small decision object instead of leaking Bucket4j types into the web layer.

### 5. Resolve keys from the server session

Use the authenticated user id from the HTTP session.

```java
public Optional<String> resolve(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    String userId = sessionAccess.sessionUserId(session);
    if (userId == null || !sessionAccess.sessionRateLimitingEnabled(session)) {
        return Optional.empty();
    }

    return Optional.of(properties.requireRedisKeyPrefix() + ":" + userId);
}
```

This keeps the limit stable across browser tabs and application instances.

### 6. Enforce the limit in a filter

Skip requests that are outside the protected method and path set.

```java
protected boolean shouldNotFilter(HttpServletRequest request) {
    return !properties.isEnabled() || !isProtectedRequest(request);
}

private boolean isProtectedRequest(HttpServletRequest request) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
        return false;
    }

    return properties.getProtectedPaths().contains(pathWithinApplication(request));
}
```

Consume a token before the request reaches the controller.

```java
RateLimitDecision decision = rateLimitService.consume(key.get());
response.setHeader("X-Rate-Limit-Limit", String.valueOf(properties.requireRequestsPerMinute()));
response.setHeader("X-Rate-Limit-Remaining", String.valueOf(decision.remainingTokens()));

if (decision.allowed()) {
    filterChain.doFilter(request, response);
    return;
}

long retryAfterSeconds = decision.retryAfterSeconds();
response.setStatus(429);
response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
response.setHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(retryAfterSeconds));
response.setContentType("application/json");
response.getWriter().write("{\"message\":\"Rate limit exceeded. Try again later.\"}");
```

Round retry time up to whole seconds so clients can sleep for the returned duration.

```java
public long retryAfterSeconds() {
    if (retryAfter == null || retryAfter.isZero() || retryAfter.isNegative()) {
        return 0;
    }

    long nanos = retryAfter.toNanos();
    return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
}
```

### 7. Expose status to the UI

The context endpoint returns current limit and remaining tokens.

```java
public RateLimitStatus status(HttpSession session) {
    String userId = sessionAccess.sessionUserId(session);
    if (userId == null || !sessionAccess.sessionRateLimitingEnabled(session)) {
        return defaultStatus();
    }

    return rateLimitService.status(properties.requireRedisKeyPrefix() + ":" + userId);
}
```

Unauthenticated users get the default full limit because they do not have a bucket key yet.

## Tests

Write tests for these behaviors.

1. Under limit requests continue and include limit headers.
2. Over limit requests stop at the filter and return `429`.
3. Rejections include `Retry-After`.
4. Unauthenticated requests skip the limiter.
5. Requests outside protected paths skip the limiter.
6. Non `POST` requests skip the limiter.
7. The key resolver uses the HTTP session user id.
8. Disabled session preference returns no key.
9. Status returns the current bucket state for logged in users.
10. Invalid configuration fails early.
