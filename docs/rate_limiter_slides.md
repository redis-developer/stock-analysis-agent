# Rate Limiter Slide Structure

## Deck Goal

Explain how the application enforces per user request limits with Redis, Bucket4j, and a Spring Boot filter.

## Audience

Backend engineers implementing shared rate limiting for API endpoints.

## Slide 1: Title

Title: Rate Limiting With Redis

Talking points:

1. Each authenticated user gets one Redis backed token bucket.
2. Each protected chat request consumes one token.
3. Redis stores the bucket state for every app instance.

Visual:

1. User.
2. App filter.
3. Redis bucket state.
4. Chat controller.

Repo anchors:

1. `docs/rate_limiter.md`
2. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingFilter.java`

## Slide 2: Protected Surface

Title: Which Requests Are Limited

Talking points:

1. The filter protects `POST /api/chat`.
2. The filter protects `POST /api/chat/stream`.
3. Login, settings, context, sessions, logout, and cache inspection routes are outside this limiter.
4. Exact path matching keeps the protected surface explicit.

Config callout:

```yaml
app:
  rate-limiting:
    protected-paths:
      - /api/chat
      - /api/chat/stream
```

Repo anchors:

1. `src/main/resources/application.yaml`
2. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingFilter.java`

## Slide 3: Key Model

Title: One Bucket Per User

Talking points:

1. The key resolver reads `userId` from the HTTP session.
2. The Redis key includes the configured prefix and user id.
3. Missing users bypass the limiter.
4. Users can disable rate limiting through session settings.

Slide content:

```text
stock-analysis:rate-limit:user:{userId}
```

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/ratelimiting/HttpSessionRateLimitKeyResolver.java`

## Slide 4: Token Bucket Behavior

Title: How The Bucket Refills

Talking points:

1. The default limit is 6 requests.
2. The default refill period is 1 minute.
3. Buckets start full.
4. Each protected request consumes 1 token.
5. Bucket4j refills the bucket back to capacity on the configured interval.
6. Bucket4j stores opaque bucket state in Redis.

Visual:

1. Bucket with 6 tokens.
2. Requests consuming tokens.
3. Refill after 1 minute.

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RedisBucketRateLimitService.java`

## Slide 5: Redis Connection

Title: Bucket4j Uses Redis

Talking points:

1. Lettuce connects to the configured Redis host and port.
2. Username and password come from Redis configuration.
3. Bucket4j uses a Redis `ProxyManager`.
4. Idle bucket keys expire after enough time has passed to refill the bucket.
5. Redis 8 is enough for this implementation.

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingConfig.java`

## Slide 6: Filter Flow

Title: Request Enforcement

Talking points:

1. The filter runs before the controller.
2. Unprotected requests continue without consuming a token.
3. Requests without a key continue without consuming a token.
4. Requests with a key call `RateLimitService.consume`.
5. Allowed requests continue through the filter chain.
6. Blocked requests return `429`.

Visual:

1. Request.
2. Protected path check.
3. Key resolve.
4. Consume token.
5. Allow or reject.

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingFilter.java`

## Slide 7: Response Headers

Title: Client Feedback

Talking points:

1. Allowed responses include `X-Rate-Limit-Limit`.
2. Allowed responses include `X-Rate-Limit-Remaining`.
3. Blocked responses include `Retry-After`.
4. Blocked responses include `X-Rate-Limit-Retry-After-Seconds`.
5. Blocked responses return a JSON message.

Slide content:

```text
X-Rate-Limit-Limit: 6
X-Rate-Limit-Remaining: 5
Retry-After: 12
```

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingFilter.java`
2. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitDecision.java`

## Slide 8: Status Endpoint

Title: Showing Remaining Tokens

Talking points:

1. `GET /api/chat/context` returns rate limit status.
2. Logged in users get current bucket state.
3. Users without a bucket get the default full limit.
4. The UI can render remaining tokens before the next chat request.

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitStatusProvider.java`
2. `src/main/java/com/redis/stockanalysisagent/session/controller/ChatSessionController.java`

## Slide 9: Configuration

Title: Tunable Settings

Talking points:

1. `enabled` turns the limiter on or off.
2. `requestsPerMinute` sets bucket capacity and refill amount.
3. `refillPeriod` sets refill frequency.
4. `redisKeyPrefix` controls key namespace.
5. `protectedPaths` controls which endpoints consume tokens.
6. Invalid limit and refill values fail early.

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/ratelimiting/RateLimitingProperties.java`

## Slide 10: Implementation Checklist

Title: Build Steps

Talking points:

1. Define rate limit properties.
2. Create Redis client and Bucket4j proxy manager.
3. Implement a service that consumes one token per request.
4. Resolve bucket keys from server session state.
5. Enforce the limiter in a request filter.
6. Return standard `429` responses with retry headers.
7. Expose current status for the UI.
8. Test allowed, blocked, unauthenticated, unprotected, and disabled cases.

Visual:

1. Checklist with eight rows.

