# Session Management With Redis

Use Redis as the shared HTTP session store. Every application instance can read the same login state, user preferences, and cached session list.

This repository uses Spring Session with Redis for HTTP session state. It stores chat working memory through Agent Memory Server, with Redis as the backing database. The same design works in other stacks if the same responsibilities stay separate.

Redis 8 is enough for this implementation. Redis Stack is not required.

## Reference Implementation

1. `src/main/java/com/redis/stockanalysisagent/sessionmanagement/SessionConfig.java`
2. `src/main/java/com/redis/stockanalysisagent/session/ChatSessionAccess.java`
3. `src/main/java/com/redis/stockanalysisagent/session/controller/ChatSessionController.java`
4. `src/main/java/com/redis/stockanalysisagent/session/ChatSessionService.java`
5. `src/main/java/com/redis/stockanalysisagent/session/ConversationId.java`
6. `src/main/java/com/redis/stockanalysisagent/memory/AmsChatMemoryRepository.java`
7. `src/main/java/com/redis/stockanalysisagent/chat/ChatController.java`
8. `src/main/java/com/redis/stockanalysisagent/chat/ChatService.java`

## Data Model

HTTP session data lives in Redis through Spring Session. The repository configures the namespace and timeout in `application.yaml`.

```yaml
spring:
  session:
    redis:
      namespace: stock-analysis:session
    timeout: 30m
```

The application stores only server controlled values in the HTTP session.

```text
stockAnalysisUserId
stockAnalysisRetrievedMemoriesLimit
stockAnalysisApiCachingEnabled
stockAnalysisSemanticCachingEnabled
stockAnalysisRateLimitingEnabled
stockAnalysisChatSessions
```

Chat memory uses a separate conversation id.

```text
{userId}:{sessionId}
```

The client sends `sessionId`. The server reads `userId` from the HTTP session and combines both values before reading or writing memory. This prevents one user from loading another user session by changing a request body field.

The HTTP session has an active 30 minute TTL through Spring Session.

The chat transcript TTL is present as intent in `AmsChatMemoryRepository`, where `DEFAULT_TTL_SECONDS` is `1800`. In this repository, `AgentMemoryService.putWorkingMemory` is a no op because the current Agent Memory Server integration appends and deletes session memory. If you implement this pattern in your own codebase, apply transcript TTL in the write path that actually persists the transcript.

## Flow

1. `POST /api/chat/login` creates an HTTP session and stores the user id plus preferences.
2. `POST /api/chat/settings` updates preferences on the existing HTTP session.
3. `GET /api/chat/context` returns the current session state and rate limit status.
4. `POST /api/chat` requires a logged in user, normalizes the requested chat session id, runs the analysis, saves the turn, and records the active session id in the session list cache.
5. `GET /api/chat/sessions` returns the cached session list from the HTTP session when present. `forceRefresh=true` reloads it from memory.
6. `GET /api/chat/session/{sessionId}` loads messages for the current user and requested session.
7. `DELETE /api/chat/session/{sessionId}` clears that chat memory and removes the id from the cached session list.
8. `POST /api/chat/logout` invalidates the HTTP session.

## Implementation Steps

### 1. Add Redis session support

In Spring, add Redis data and session dependencies.

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-redis")
implementation("org.springframework.session:spring-session-data-redis")
```

For Spring Boot auto configuration, use the Spring Session Redis starter instead of direct Spring Session wiring.

```kotlin
implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
```

For another framework, use the framework session middleware with Redis as the store.

### 2. Configure the Redis session store

Enable Redis backed HTTP sessions and use JSON serialization for values you store yourself.

```java
@Configuration
@EnableRedisHttpSession
public class SessionConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
```

Keep the session TTL in configuration. The value should match how long an idle user session should remain valid.

### 3. Know the Spring configuration options

Spring Boot can configure Spring Session Redis through auto configuration. Current Spring Boot uses `spring.session.data.redis.*` for Redis specific session settings. Older Spring Boot applications often use `spring.session.redis.*`.

This repository has an explicit `@EnableRedisHttpSession`. Spring Boot documentation states that using `@Enable*HttpSession` takes control over Spring Session configuration and causes Boot session auto configuration to back off. In that setup, use annotation attributes for the core Spring Session settings, or remove the annotation and rely on Boot auto configuration.

Property based configuration for Spring Boot 4:

```yaml
spring:
  session:
    timeout: 30m
    data:
      redis:
        namespace: stock-analysis:session
        flush-mode: on-save
        save-mode: on-set-attribute
        repository-type: default
        cleanup-cron: "0 * * * * *"
        configure-action: notify-keyspace-events
```

Common properties:

1. `spring.session.timeout` sets the inactive session timeout. If it is not set in a servlet app, Spring Boot can fall back to `server.servlet.session.timeout`.
2. `spring.session.data.redis.namespace` sets the Redis key namespace. The default is `spring:session`.
3. `spring.session.data.redis.flush-mode` controls when session writes are flushed. `on-save` writes when the session is saved near response commit. `immediate` writes changes as they happen.
4. `spring.session.data.redis.save-mode` controls which attributes are saved. `on-set-attribute` saves changed attributes. `on-get-attribute` also saves attributes that were read. `always` saves all attributes.
5. `spring.session.data.redis.repository-type` controls the repository implementation. `default` uses the standard Redis session repository. `indexed` uses the indexed repository.
6. `spring.session.data.redis.cleanup-cron` configures expired session cleanup for the indexed repository.
7. `spring.session.data.redis.configure-action` controls Redis keyspace notification setup. `notify-keyspace-events` asks Spring to configure Redis for session expiration events. `none` avoids issuing Redis configuration commands.

Annotation based configuration:

```java
@Configuration
@EnableRedisHttpSession(
        redisNamespace = "stock-analysis:session",
        maxInactiveIntervalInSeconds = 1800,
        flushMode = FlushMode.ON_SAVE,
        saveMode = SaveMode.ON_SET_ATTRIBUTE
)
public class SessionConfig {
}
```

Servlet cookie settings are configured separately from Spring Session:

```yaml
server:
  servlet:
    session:
      cookie:
        name: STOCK_ANALYSIS_SESSION
        http-only: true
        secure: true
        same-site: strict
        path: /
        max-age: 30m
```

Useful servlet session properties:

1. `server.servlet.session.cookie.name` sets the cookie name.
2. `server.servlet.session.cookie.http-only` prevents browser JavaScript from reading the cookie.
3. `server.servlet.session.cookie.secure` sends the cookie only over HTTPS.
4. `server.servlet.session.cookie.same-site` controls cross site cookie behavior.
5. `server.servlet.session.cookie.domain` and `server.servlet.session.cookie.path` control where the cookie is sent.
6. `server.servlet.session.cookie.max-age` controls cookie lifetime.
7. `server.servlet.session.tracking-modes` controls whether sessions use cookies, URLs, or SSL tracking.

### 4. Centralize session reads and writes

Put session attribute names and validation in one component. Controllers should call the helper instead of reading arbitrary attributes.

```java
public String requireSessionUserId(HttpSession session) {
    String userId = sessionUserId(session);
    if (userId == null) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required.");
    }
    return userId;
}

public String sessionUserId(HttpSession session) {
    if (session == null) {
        return null;
    }

    Object value = session.getAttribute("stockAnalysisUserId");
    return value instanceof String userId && !userId.isBlank()
            ? userId.trim()
            : null;
}
```

Normalize preferences before storing them.

```java
public int normalizeRetrievedMemoriesLimit(Integer value) {
    if (value == null) {
        return 10;
    }

    return Math.min(20, Math.max(1, value));
}

public boolean normalizeCachingEnabled(Boolean value) {
    return value == null || value;
}
```

### 5. Store login state on the server

Login should store the user id and request preferences in the HTTP session.

```java
HttpSession session = request.getSession(true);
String userId = sessionAccess.requireUserId(login.userId());

sessionAccess.storeUserId(session, userId);
sessionAccess.storeRetrievedMemoriesLimit(session, normalizedLimit);
sessionAccess.storeApiCachingEnabled(session, apiCachingEnabled);
sessionAccess.storeSemanticCachingEnabled(session, semanticCachingEnabled);
sessionAccess.storeRateLimitingEnabled(session, rateLimitingEnabled);
sessionAccess.clearCachedChatSessions(session);
```

After login, chat requests should read `userId` from the HTTP session.

```java
HttpSession session = httpRequest.getSession(false);
String userId = sessionAccess.activeUserId(session);
String sessionId = sessionAccess.normalizeSessionId(request.sessionId());
```

### 6. Build conversation ids on the server

Store chat history under a user scoped conversation id.

```java
public record ConversationId(String value, String userId, String sessionId) {

    public static ConversationId of(String userId, String sessionId) {
        return new ConversationId(userId + ":" + sessionId, userId, sessionId);
    }
}
```

Use this id for all memory reads and writes.

```java
String conversationId = ConversationId.of(userId, sessionId).value();
chatMemory.add(conversationId, new UserMessage(message));
chatMemory.add(conversationId, new AssistantMessage(response));
```

### 7. Cache the session list inside the HTTP session

The list of chat sessions can be expensive to load. Store it in the HTTP session after the first read.

```java
List<String> sessions = sessionAccess.cachedChatSessions(session);
if (forceRefresh || sessions == null) {
    sessions = sessionAccess.normalizeSessionIds(chatSessionService.listSessions(userId));
    sessionAccess.storeCachedChatSessions(session, sessions);
}
```

When a chat turn succeeds, add the active session id to the front of that cached list.

```java
sessionAccess.cacheChatSession(prepared.session(), prepared.sessionId());
```

When a session is deleted, remove it from the cached list.

```java
chatSessionService.clearSession(userId, normalizedSessionId);
sessionAccess.removeCachedChatSession(session, normalizedSessionId);
```

### 8. Keep memory access user scoped

Session list and message reads should accept `userId`.

```java
public List<String> listSessions(String userId) {
    return memoryRepository.findConversationIds(userId).stream()
            .filter(sessionId -> sessionId != null && !sessionId.isBlank())
            .distinct()
            .sorted(Comparator.reverseOrder())
            .toList();
}

public List<ChatSessionMessage> getSessionMessages(String userId, String sessionId) {
    String conversationId = ConversationId.of(userId, sessionId).value();
    return memoryRepository.findByConversationId(conversationId).stream()
            .map(this::toSessionMessage)
            .filter(message -> message != null && !message.content().isBlank())
            .toList();
}
```

## Tests

Write tests for these behaviors.

1. Context without login returns defaults and no active user.
2. Login stores user id and preferences in the HTTP session.
3. Settings updates preferences and requires login.
4. Session list loads once, then returns the session cached copy.
5. `forceRefresh=true` reloads session ids from memory.
6. Delete clears memory and removes the id from the cached list.
7. Session endpoints return forbidden when session management is disabled.
8. Chat requests reject missing login state.
