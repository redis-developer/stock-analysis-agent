package com.redis.stockanalysisagent.cache;

import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class CacheInspectionService {

    private static final int MAX_ENTRIES_PER_CACHE = 50;
    private static final int MAX_VALUE_PREVIEW_CHARACTERS = 4_000;
    private static final long SCAN_COUNT = 100;

    private final CacheManager cacheManager;
    private final RedisConnectionFactory connectionFactory;
    private final GenericJacksonJsonRedisSerializer valueSerializer;
    private final ObjectMapper objectMapper;

    public CacheInspectionService(CacheManager cacheManager, RedisConnectionFactory connectionFactory) {
        this.cacheManager = cacheManager;
        this.connectionFactory = connectionFactory;
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        this.valueSerializer = new GenericJacksonJsonRedisSerializer(objectMapper);
    }

    public CacheContents inspect() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return new CacheContents(cacheNames().stream()
                    .map(cacheName -> inspectCache(connection, cacheName))
                    .toList());
        }
    }

    public boolean deleteEntry(String cacheName, String key) {
        if (!CacheNames.externalDataCacheNames().contains(cacheName)) {
            return false;
        }

        String normalizedKey = key == null ? "" : key.trim();
        if (normalizedKey.isBlank()) {
            return false;
        }

        byte[] redisKey = (cacheName + "::" + normalizedKey).getBytes(StandardCharsets.UTF_8);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Long deleted = connection.keyCommands().del(redisKey);
            return deleted != null && deleted > 0;
        }
    }

    private List<String> cacheNames() {
        Set<String> names = new LinkedHashSet<>(CacheNames.externalDataCacheNames());
        names.addAll(cacheManager.getCacheNames());
        return names.stream()
                .filter(CacheNames.externalDataCacheNames()::contains)
                .toList();
    }

    private CacheGroup inspectCache(RedisConnection connection, String cacheName) {
        String keyPrefix = cacheName + "::";
        List<CacheEntry> entries = new ArrayList<>();
        boolean truncated = false;

        ScanOptions options = ScanOptions.scanOptions()
                .match(keyPrefix + "*")
                .count(SCAN_COUNT)
                .build();

        try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
            while (cursor.hasNext()) {
                byte[] redisKey = cursor.next();
                if (entries.size() >= MAX_ENTRIES_PER_CACHE) {
                    truncated = true;
                    break;
                }
                entries.add(inspectEntry(connection, keyPrefix, redisKey));
            }
        }

        return new CacheGroup(cacheName, entries.size(), truncated, entries);
    }

    private CacheEntry inspectEntry(RedisConnection connection, String keyPrefix, byte[] redisKey) {
        String fullKey = new String(redisKey, StandardCharsets.UTF_8);
        String key = fullKey.startsWith(keyPrefix)
                ? fullKey.substring(keyPrefix.length())
                : fullKey;
        Long ttlSeconds = connection.keyCommands().ttl(redisKey);
        byte[] rawValue = connection.stringCommands().get(redisKey);
        Object value = deserialize(rawValue);
        String textValue = stringify(value, rawValue);

        return new CacheEntry(
                key,
                normalizeTtl(ttlSeconds),
                value == null ? "unknown" : value.getClass().getSimpleName(),
                rawValue == null ? 0 : rawValue.length,
                isTruncated(textValue),
                truncate(textValue)
        );
    }

    private Object deserialize(byte[] rawValue) {
        if (rawValue == null || rawValue.length == 0) {
            return null;
        }

        try {
            return valueSerializer.deserialize(rawValue);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String stringify(Object value, byte[] rawValue) {
        if (value == null) {
            return rawValue == null ? "" : new String(rawValue, StandardCharsets.UTF_8);
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException ignored) {
            return value.toString();
        }
    }

    private boolean isTruncated(String value) {
        return value != null && value.length() > MAX_VALUE_PREVIEW_CHARACTERS;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_VALUE_PREVIEW_CHARACTERS) {
            return value == null ? "" : value;
        }

        return value.substring(0, MAX_VALUE_PREVIEW_CHARACTERS) + "\n[Truncated]";
    }

    private Long normalizeTtl(Long ttlSeconds) {
        if (ttlSeconds == null || ttlSeconds < 0) {
            return null;
        }

        return ttlSeconds;
    }

    public record CacheContents(
            List<CacheGroup> caches
    ) {
    }

    public record CacheGroup(
            String name,
            int entryCount,
            boolean truncated,
            List<CacheEntry> entries
    ) {
    }

    public record CacheEntry(
            String key,
            Long ttlSeconds,
            String valueType,
            int valueSizeBytes,
            boolean valueTruncated,
            String value
    ) {
    }
}
