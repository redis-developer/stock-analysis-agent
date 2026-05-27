package com.redis.stockanalysisagent.session;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public final class ChatSessionAccess {

    public static final int DEFAULT_RETRIEVED_MEMORIES_LIMIT = 10;

    private static final String USER_ID_SESSION_ATTRIBUTE = "stockAnalysisUserId";
    private static final String RETRIEVED_MEMORIES_LIMIT_SESSION_ATTRIBUTE = "stockAnalysisRetrievedMemoriesLimit";
    private static final String API_CACHING_ENABLED_SESSION_ATTRIBUTE = "stockAnalysisApiCachingEnabled";
    private static final String SEMANTIC_CACHING_ENABLED_SESSION_ATTRIBUTE = "stockAnalysisSemanticCachingEnabled";
    private static final String RATE_LIMITING_ENABLED_SESSION_ATTRIBUTE = "stockAnalysisRateLimitingEnabled";
    private static final String CHAT_SESSIONS_SESSION_ATTRIBUTE = "stockAnalysisChatSessions";
    private static final int MAX_RETRIEVED_MEMORIES_LIMIT = 20;

    private final boolean sessionManagementEnabled;

    public ChatSessionAccess(
            @Value("${app.chat.session-management-enabled:true}") boolean sessionManagementEnabled
    ) {
        this.sessionManagementEnabled = sessionManagementEnabled;
    }

    public int defaultRetrievedMemoriesLimit() {
        return DEFAULT_RETRIEVED_MEMORIES_LIMIT;
    }

    public boolean sessionManagementEnabled() {
        return sessionManagementEnabled;
    }

    public String activeUserId(HttpSession session) {
        return requireSessionUserId(session);
    }

    public int activeRetrievedMemoriesLimit(HttpSession session) {
        return sessionRetrievedMemoriesLimit(session);
    }

    public boolean activeApiCachingEnabled(HttpSession session) {
        return sessionApiCachingEnabled(session);
    }

    public boolean activeSemanticCachingEnabled(HttpSession session) {
        return sessionSemanticCachingEnabled(session);
    }

    public boolean activeRateLimitingEnabled(HttpSession session) {
        return sessionRateLimitingEnabled(session);
    }

    public int sessionRetrievedMemoriesLimit(HttpSession session) {
        if (session == null) {
            return DEFAULT_RETRIEVED_MEMORIES_LIMIT;
        }

        Object value = session.getAttribute(RETRIEVED_MEMORIES_LIMIT_SESSION_ATTRIBUTE);
        if (value instanceof Number number) {
            return normalizeRetrievedMemoriesLimit(number.intValue());
        }

        return DEFAULT_RETRIEVED_MEMORIES_LIMIT;
    }

    public boolean sessionApiCachingEnabled(HttpSession session) {
        return sessionBoolean(session, API_CACHING_ENABLED_SESSION_ATTRIBUTE);
    }

    public boolean sessionSemanticCachingEnabled(HttpSession session) {
        return sessionBoolean(session, SEMANTIC_CACHING_ENABLED_SESSION_ATTRIBUTE);
    }

    public boolean sessionRateLimitingEnabled(HttpSession session) {
        return sessionBoolean(session, RATE_LIMITING_ENABLED_SESSION_ATTRIBUTE);
    }

    public int normalizeRetrievedMemoriesLimit(Integer value) {
        if (value == null) {
            return DEFAULT_RETRIEVED_MEMORIES_LIMIT;
        }

        return Math.min(MAX_RETRIEVED_MEMORIES_LIMIT, Math.max(1, value));
    }

    public boolean normalizeCachingEnabled(Boolean value) {
        return value == null || value;
    }

    public boolean normalizeRateLimitingEnabled(Boolean value) {
        return value == null || value;
    }

    public void storeUserId(HttpSession session, String userId) {
        session.setAttribute(USER_ID_SESSION_ATTRIBUTE, userId);
    }

    public void storeRetrievedMemoriesLimit(HttpSession session, int retrievedMemoriesLimit) {
        session.setAttribute(RETRIEVED_MEMORIES_LIMIT_SESSION_ATTRIBUTE, retrievedMemoriesLimit);
    }

    public void storeApiCachingEnabled(HttpSession session, boolean apiCachingEnabled) {
        session.setAttribute(API_CACHING_ENABLED_SESSION_ATTRIBUTE, apiCachingEnabled);
    }

    public void storeSemanticCachingEnabled(HttpSession session, boolean semanticCachingEnabled) {
        session.setAttribute(SEMANTIC_CACHING_ENABLED_SESSION_ATTRIBUTE, semanticCachingEnabled);
    }

    public void storeRateLimitingEnabled(HttpSession session, boolean rateLimitingEnabled) {
        session.setAttribute(RATE_LIMITING_ENABLED_SESSION_ATTRIBUTE, rateLimitingEnabled);
    }

    public void clearCachedChatSessions(HttpSession session) {
        session.removeAttribute(CHAT_SESSIONS_SESSION_ATTRIBUTE);
    }

    public List<String> cachedChatSessions(HttpSession session) {
        if (session == null) {
            return null;
        }

        Object value = session.getAttribute(CHAT_SESSIONS_SESSION_ATTRIBUTE);
        if (!(value instanceof Iterable<?> sessions)) {
            return null;
        }

        return normalizeSessionIds(sessions);
    }

    public void storeCachedChatSessions(HttpSession session, List<String> sessions) {
        session.setAttribute(CHAT_SESSIONS_SESSION_ATTRIBUTE, new ArrayList<>(sessions));
    }

    public void cacheChatSession(HttpSession session, String sessionId) {
        if (!sessionManagementEnabled || session == null) {
            return;
        }

        String normalizedSessionId = requireExistingSessionId(sessionId);
        List<String> cachedSessions = cachedChatSessions(session);
        if (cachedSessions == null) {
            return;
        }

        List<String> sessions = new ArrayList<>();
        sessions.add(normalizedSessionId);

        for (String cachedSessionId : cachedSessions) {
            if (!normalizedSessionId.equals(cachedSessionId)) {
                sessions.add(cachedSessionId);
            }
        }

        storeCachedChatSessions(session, sessions);
    }

    public void removeCachedChatSession(HttpSession session, String sessionId) {
        List<String> cachedSessions = cachedChatSessions(session);
        if (cachedSessions == null) {
            return;
        }

        List<String> sessions = cachedSessions.stream()
                .filter(cachedSessionId -> !sessionId.equals(cachedSessionId))
                .toList();
        storeCachedChatSessions(session, sessions);
    }

    public List<String> normalizeSessionIds(Iterable<?> sessionIds) {
        List<String> normalized = new ArrayList<>();
        if (sessionIds == null) {
            return normalized;
        }

        for (Object sessionId : sessionIds) {
            if (!(sessionId instanceof String value)) {
                continue;
            }

            String normalizedSessionId = value.trim();
            if (!normalizedSessionId.isBlank() && !normalized.contains(normalizedSessionId)) {
                normalized.add(normalizedSessionId);
            }
        }

        return new ArrayList<>(normalized);
    }

    public String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }

        return sessionId.trim();
    }

    public String requireExistingSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session id is required.");
        }

        return sessionId.trim();
    }

    public void requireSessionManagementEnabled() {
        if (!sessionManagementEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session management is disabled.");
        }
    }

    public String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User id is required.");
        }

        return userId.trim();
    }

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

        Object value = session.getAttribute(USER_ID_SESSION_ATTRIBUTE);
        return value instanceof String userId && !userId.isBlank()
                ? userId.trim()
                : null;
    }

    private boolean sessionBoolean(HttpSession session, String attributeName) {
        if (session == null) {
            return true;
        }

        Object value = session.getAttribute(attributeName);
        return !(value instanceof Boolean enabled) || enabled;
    }
}
