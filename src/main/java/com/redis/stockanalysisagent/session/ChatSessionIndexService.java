package com.redis.stockanalysisagent.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ChatSessionIndexService {

    private static final String USER_SESSION_INDEX_PREFIX = "stock-analysis:user:";
    private static final String USER_SESSION_INDEX_SUFFIX = ":sessions";

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Autowired
    public ChatSessionIndexService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC());
    }

    ChatSessionIndexService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public void recordSessionStarted(String userId, String sessionId) {
        recordSessionActivity(userId, sessionId, clock.instant());
    }

    public void recordSessionCompleted(String userId, String sessionId) {
        recordSessionActivity(userId, sessionId, clock.instant());
    }

    public List<String> listSessions(String userId) {
        String key = sessionsKey(userId);
        if (key == null) {
            return List.of();
        }

        Set<String> sessions = redisTemplate.opsForZSet().reverseRange(key, 0, -1);
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(sessions);
    }

    public void removeSession(String userId, String sessionId) {
        String key = sessionsKey(userId);
        String normalizedSessionId = normalize(sessionId);
        if (key == null || normalizedSessionId == null) {
            return;
        }

        redisTemplate.opsForZSet().remove(key, normalizedSessionId);
    }

    static String sessionsKey(String userId) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId == null) {
            return null;
        }

        return USER_SESSION_INDEX_PREFIX + normalizedUserId + USER_SESSION_INDEX_SUFFIX;
    }

    private void recordSessionActivity(String userId, String sessionId, Instant activityAt) {
        String key = sessionsKey(userId);
        String normalizedSessionId = normalize(sessionId);
        if (key == null || normalizedSessionId == null) {
            return;
        }

        redisTemplate.opsForZSet().add(key, normalizedSessionId, activityAt.toEpochMilli());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
