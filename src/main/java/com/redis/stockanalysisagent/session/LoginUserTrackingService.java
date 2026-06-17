package com.redis.stockanalysisagent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LoginUserTrackingService {

    static final String KEY_PREFIX = "stock-analysis:users:";
    static final int MAX_IP_ADDRESSES = 20;
    static final int MAX_USER_AGENTS = 10;
    static final int MAX_ACCEPT_LANGUAGES = 10;

    private static final String JSON_ROOT_PATH = "$";
    private static final TypeReference<Map<String, Object>> PROFILE_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public LoginUserTrackingService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new ObjectMapper(), Clock.systemUTC());
    }

    LoginUserTrackingService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void recordLogin(String username) {
        recordLogin(username, null, null, null, null);
    }

    public void recordLogin(String username, String ipAddress, String userAgent) {
        recordLogin(username, ipAddress, userAgent, null, null);
    }

    public void recordLogin(
            String username,
            String ipAddress,
            String userAgent,
            String acceptLanguage,
            String sessionId
    ) {
        if (username == null || username.isBlank()) {
            return;
        }

        String normalizedUsername = username.trim();
        String seenAt = Instant.now(clock).toString();
        String key = redisKey(normalizedUsername);

        Map<String, Object> profile = readProfile(key);
        profile.putIfAbsent("username", normalizedUsername);
        profile.putIfAbsent("firstSeenAt", seenAt);
        profile.put("lastSeenAt", seenAt);
        profile.put("loginCount", longValue(profile.get("loginCount")) + 1);
        trackListValue(profile, "ipAddresses", "lastIpAddress", ipAddress, MAX_IP_ADDRESSES);
        trackListValue(profile, "userAgents", "lastUserAgent", userAgent, MAX_USER_AGENTS);
        trackListValue(profile, "acceptLanguages", "lastAcceptLanguage", acceptLanguage, MAX_ACCEPT_LANGUAGES);
        putIfPresent(profile, "lastSessionId", sessionId);
        writeProfile(key, profile);
    }

    private Map<String, Object> readProfile(String key) {
        Object result = executeJsonGet(key);
        String json = decode(result);
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }

        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, PROFILE_TYPE));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not deserialize tracked user profile.", ex);
        }
    }

    private void writeProfile(String key, Map<String, Object> profile) {
        try {
            String json = objectMapper.writeValueAsString(profile);
            executeJsonSet(key, json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize tracked user profile.", ex);
        }
    }

    Object executeJsonGet(String key) {
        return redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.commands().execute("JSON.GET", bytes(key)));
    }

    void executeJsonSet(String key, String json) {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.commands().execute("JSON.SET", bytes(key), bytes(JSON_ROOT_PATH), bytes(json));
            return null;
        });
    }

    private void trackListValue(
            Map<String, Object> profile,
            String listField,
            String lastValueField,
            String value,
            int maxValues
    ) {
        if (value == null || value.isBlank()) {
            return;
        }

        String normalizedValue = value.trim();
        List<String> values = stringList(profile.get(listField));
        values.remove(normalizedValue);
        values.add(normalizedValue);
        while (values.size() > maxValues) {
            values.removeFirst();
        }

        profile.put(listField, values);
        profile.put(lastValueField, normalizedValue);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return new ArrayList<>();
        }

        List<String> normalized = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof String text && !text.isBlank() && !normalized.contains(text.trim())) {
                normalized.add(text.trim());
            }
        }
        return normalized;
    }

    private void putIfPresent(Map<String, Object> profile, String field, String value) {
        if (value != null && !value.isBlank()) {
            profile.put(field, value.trim());
        }
    }

    private long longValue(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private String decode(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value instanceof String text ? text : null;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String redisKey(String username) {
        return KEY_PREFIX + username;
    }
}
