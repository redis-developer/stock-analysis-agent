package com.redis.stockanalysisagent.reliability;

import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ProviderDeadLetterService {

    static final String KEY = "stock-analysis:provider-dead-letter";

    private static final Logger log = LoggerFactory.getLogger(ProviderDeadLetterService.class);

    private final StringRedisTemplate redisTemplate;
    private final ProviderRetryProperties properties;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    @Autowired
    public ProviderDeadLetterService(StringRedisTemplate redisTemplate, ProviderRetryProperties properties) {
        this(redisTemplate, properties, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    ProviderDeadLetterService(
            StringRedisTemplate redisTemplate,
            ProviderRetryProperties properties,
            Clock clock,
            Supplier<String> idSupplier
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
        this.idSupplier = idSupplier;
    }

    public ProviderFailureRecord append(
            String stepId,
            String providerId,
            String providerLabel,
            String cacheName,
            String cacheKey,
            int attempts,
            RuntimeException failure
    ) {
        String failedAt = Instant.now(clock).toString();
        ProviderFailureRecord record = new ProviderFailureRecord(
                idSupplier.get(),
                WorkflowContextHolder.workflowId().orElse(""),
                value(stepId),
                CircuitBreakerService.normalizeProviderId(providerId),
                value(providerLabel),
                value(cacheName),
                value(cacheKey),
                Math.max(0, attempts),
                reason(failure),
                failedAt
        );
        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForStream().add(KEY, fields(record));
                operations.expire(KEY, properties.getDeadLetterTtl());
                return null;
            }
        });
        log.info(
                "provider_dead_letter_recorded failureId={} workflowId={} provider={} attempts={} reason={}",
                record.failureId(),
                record.workflowId(),
                record.providerId(),
                record.attempts(),
                record.reason()
        );
        return record;
    }

    public List<ProviderFailureRecord> recent() {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .reverseRange(KEY, Range.unbounded(), Limit.limit().count(properties.getDeadLetterReadLimit()));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(MapRecord::getValue)
                .map(this::record)
                .toList();
    }

    private Map<String, String> fields(ProviderFailureRecord record) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("failureId", record.failureId());
        fields.put("workflowId", record.workflowId());
        fields.put("stepId", record.stepId());
        fields.put("providerId", record.providerId());
        fields.put("providerLabel", record.providerLabel());
        fields.put("cacheName", record.cacheName());
        fields.put("cacheKey", record.cacheKey());
        fields.put("attempts", String.valueOf(record.attempts()));
        fields.put("reason", record.reason());
        fields.put("failedAt", record.failedAt());
        return fields;
    }

    private ProviderFailureRecord record(Map<Object, Object> fields) {
        return new ProviderFailureRecord(
                field(fields, "failureId"),
                field(fields, "workflowId"),
                field(fields, "stepId"),
                field(fields, "providerId"),
                field(fields, "providerLabel"),
                field(fields, "cacheName"),
                field(fields, "cacheKey"),
                integerField(fields, "attempts"),
                field(fields, "reason"),
                field(fields, "failedAt")
        );
    }

    private int integerField(Map<Object, Object> fields, String name) {
        try {
            return Integer.parseInt(field(fields, name));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String reason(RuntimeException failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        if ((message == null || message.isBlank()) && failure.getCause() instanceof RuntimeException cause) {
            message = cause.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String field(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        return value == null ? "" : value.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
