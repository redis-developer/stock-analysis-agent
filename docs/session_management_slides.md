# Session Management Slide Structure

## Deck Goal

Explain how the application uses Redis backed HTTP sessions, user scoped conversation ids, and server controlled session state.

## Audience

Backend engineers implementing session management for a chat or agent application.

## Slide 1: Title

Title: Session Management With Redis

Talking points:

1. Redis stores shared HTTP session state.
2. The application stores login state and preferences on the server.
3. Chat transcripts use a separate memory store and a user scoped conversation id.

Visual:

1. Browser.
2. Spring Boot app.
3. Redis session store.
4. Agent Memory Server.

Repo anchors:

1. `docs/session_management.md`
2. `src/main/java/com/redis/stockanalysisagent/sessionmanagement/SessionConfig.java`

## Slide 2: Problem

Title: What Session Management Solves

Talking points:

1. The app needs to know who is using the chat endpoint.
2. The app needs request preferences to persist across chat turns.
3. Multiple application instances need the same session state.
4. Session history needs user ownership checks.

Visual:

1. Two app instances reading the same session from Redis.
2. A user id attached to all chat memory reads and writes.

## Slide 3: Redis HTTP Session Store

Title: HTTP Session State In Redis

Talking points:

1. Spring Session stores the HTTP session in Redis.
2. `spring.session.redis.namespace` isolates these keys.
3. `spring.session.timeout` controls idle session expiration.
4. Redis 8 is enough for this implementation.

Code or config callout:

```yaml
spring:
  session:
    redis:
      namespace: stock-analysis:session
    timeout: 30m
```

Repo anchors:

1. `src/main/resources/application.yaml`
2. `src/main/java/com/redis/stockanalysisagent/sessionmanagement/SessionConfig.java`

## Slide 4: Session Attributes

Title: Server Controlled Session Data

Talking points:

1. The app stores only values it owns in the HTTP session.
2. `userId` identifies the active user.
3. Preferences control memory retrieval, API caching, semantic caching, and rate limiting.
4. A cached session list reduces repeated memory lookups.

Slide content:

```text
stockAnalysisUserId
stockAnalysisRetrievedMemoriesLimit
stockAnalysisApiCachingEnabled
stockAnalysisSemanticCachingEnabled
stockAnalysisRateLimitingEnabled
stockAnalysisChatSessions
```

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/session/ChatSessionAccess.java`

## Slide 5: Login And Settings Flow

Title: How Session State Is Created

Talking points:

1. `POST /api/chat/login` creates the HTTP session.
2. Login stores `userId` and normalized preferences.
3. `POST /api/chat/settings` updates preferences for an existing session.
4. Logout invalidates the HTTP session.

Visual:

1. Login request.
2. Session attributes written.
3. Context response returned.

Repo anchor:

1. `src/main/java/com/redis/stockanalysisagent/session/controller/ChatSessionController.java`

## Slide 6: Conversation Id Model

Title: User Scoped Conversation Ids

Talking points:

1. The browser sends a client session id.
2. The server reads `userId` from the HTTP session.
3. The server builds `{userId}:{sessionId}`.
4. Memory reads and writes use the server built id.

Code callout:

```java
public static ConversationId of(String userId, String sessionId) {
    return new ConversationId(userId + ":" + sessionId, userId, sessionId);
}
```

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/session/ConversationId.java`
2. `src/main/java/com/redis/stockanalysisagent/chat/ChatService.java`

## Slide 7: Chat Request Flow

Title: Request To Stored Turn

Talking points:

1. The controller requires a logged in session user.
2. Request settings override session settings for that turn.
3. The app persists updated settings back into the HTTP session.
4. The chat service saves the user message and assistant response.
5. The active chat session id is added to the cached session list.

Visual:

1. `POST /api/chat`.
2. Session lookup.
3. Analysis.
4. Memory write.
5. Session list cache update.

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/chat/ChatController.java`
2. `src/main/java/com/redis/stockanalysisagent/chat/ChatService.java`

## Slide 8: Session List Management

Title: Listing And Clearing Sessions

Talking points:

1. `GET /api/chat/sessions` reads the cached session list when available.
2. `forceRefresh=true` reloads the list from memory.
3. `GET /api/chat/session/{sessionId}` returns visible user and assistant messages.
4. `DELETE /api/chat/session/{sessionId}` clears memory and updates the cached list.

Visual:

1. Sidebar session list.
2. Cached session list in HTTP session.
3. Agent Memory Server list operation.

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/session/ChatSessionService.java`
2. `src/main/java/com/redis/stockanalysisagent/session/ChatSessionAccess.java`

## Slide 9: TTL Behavior

Title: Expiration Rules

Talking points:

1. HTTP sessions expire through Spring Session after 30 minutes of idle time.
2. Chat transcript TTL is present as intent in `AmsChatMemoryRepository`.
3. In this repository, `AgentMemoryService.putWorkingMemory` does not replace memory, so that transcript TTL is not applied by the current write path.
4. Implement transcript TTL in the persistence layer that stores chat transcript records.

Repo anchors:

1. `src/main/java/com/redis/stockanalysisagent/memory/AmsChatMemoryRepository.java`
2. `src/main/java/com/redis/stockanalysisagent/memory/service/AgentMemoryService.java`

## Slide 10: Implementation Checklist

Title: Build Steps

Talking points:

1. Add Redis backed HTTP session support.
2. Centralize session attribute reads and writes.
3. Normalize all preferences before storage.
4. Build conversation ids on the server.
5. Scope memory access by user id.
6. Cache the session list inside the HTTP session.
7. Test login, settings, context, session list, clear, and missing login behavior.

Visual:

1. Checklist with seven rows.

