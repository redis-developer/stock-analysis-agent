package com.redis.stockanalysisagent.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CircuitBreakerService {

    static final String STATE_CLOSED = "CLOSED";
    static final String STATE_OPEN = "OPEN";
    static final String STATE_HALF_OPEN = "HALF_OPEN";

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);
    private static final String KEY_PREFIX = "stock-analysis:circuit-breakers:";
    private static final String PROBE_SUFFIX = ":probe";

    private final StringRedisTemplate redisTemplate;
    private final CircuitBreakerProperties properties;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;
    private final ProviderFailureSimulationService failureSimulationService;

    @Autowired
    public CircuitBreakerService(
            StringRedisTemplate redisTemplate,
            CircuitBreakerProperties properties,
            ProviderFailureSimulationService failureSimulationService
    ) {
        this(redisTemplate, properties, failureSimulationService, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    CircuitBreakerService(
            StringRedisTemplate redisTemplate,
            CircuitBreakerProperties properties,
            Clock clock,
            Supplier<String> tokenSupplier
    ) {
        this(redisTemplate, properties, null, clock, tokenSupplier);
    }

    CircuitBreakerService(
            StringRedisTemplate redisTemplate,
            CircuitBreakerProperties properties,
            ProviderFailureSimulationService failureSimulationService,
            Clock clock,
            Supplier<String> tokenSupplier
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
        this.tokenSupplier = tokenSupplier;
        this.failureSimulationService = failureSimulationService;
    }

    public <T> T call(String providerId, Supplier<T> supplier) {
        String provider = normalizeProviderId(providerId);
        if (!properties.isEnabled()) {
            throwIfSimulated(provider);
            return supplier.get();
        }

        Probe probe = beforeCall(provider);
        try {
            throwIfSimulated(provider);
            T result = supplier.get();
            recordSuccess(provider, probe);
            return result;
        } catch (RuntimeException ex) {
            recordFailure(provider, probe, ex);
            throw ex;
        }
    }

    private void throwIfSimulated(String provider) {
        if (failureSimulationService != null) {
            failureSimulationService.throwIfEnabled(provider);
        }
    }

    public CircuitBreakerState state(String providerId) {
        String provider = normalizeProviderId(providerId);
        return state(provider, fields(provider));
    }

    public CircuitBreakerState open(String providerId, String reason) {
        String provider = normalizeProviderId(providerId);
        Instant now = clock.instant();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("providerId", provider);
        fields.put("state", STATE_OPEN);
        fields.put("failureCount", String.valueOf(properties.getFailureThreshold()));
        fields.put("openedAt", now.toString());
        fields.put("lastFailureAt", now.toString());
        fields.put("updatedAt", now.toString());
        fields.put("reason", normalizeReason(reason));
        writeFields(provider, fields);
        redisTemplate.delete(probeKey(provider));
        log.info("circuit_breaker_opened provider={} reason={}", provider, fields.get("reason"));
        return state(provider);
    }

    public CircuitBreakerState close(String providerId) {
        String provider = normalizeProviderId(providerId);
        close(provider, Probe.none());
        return state(provider);
    }

    private Probe beforeCall(String provider) {
        Map<Object, Object> fields = fields(provider);
        CircuitBreakerState state = state(provider, fields);

        if (STATE_OPEN.equals(state.state()) && !openWindowElapsed(state)) {
            recordBlockedCall(provider);
            log.info("circuit_breaker_blocked provider={} state={} openUntil={}", provider, state.state(), state.openUntil());
            throw new CircuitBreakerOpenException(provider, state);
        }

        if (STATE_OPEN.equals(state.state()) || STATE_HALF_OPEN.equals(state.state())) {
            return acquireProbe(provider);
        }

        return Probe.none();
    }

    private boolean openWindowElapsed(CircuitBreakerState state) {
        return state.openUntil() != null && !state.openUntil().isAfter(clock.instant());
    }

    private Probe acquireProbe(String provider) {
        String token = tokenSupplier.get();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                probeKey(provider),
                token,
                properties.getProbeTimeout()
        );
        if (!Boolean.TRUE.equals(acquired)) {
            CircuitBreakerState state = state(provider);
            recordBlockedCall(provider);
            log.info("circuit_breaker_probe_busy provider={} state={}", provider, state.state());
            throw new CircuitBreakerOpenException(provider, state);
        }

        Instant now = clock.instant();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("providerId", provider);
        fields.put("state", STATE_HALF_OPEN);
        fields.put("halfOpenAt", now.toString());
        fields.put("updatedAt", now.toString());
        writeFields(provider, fields);
        log.info("circuit_breaker_probe_acquired provider={}", provider);
        return new Probe(token);
    }

    private void recordSuccess(String provider, Probe probe) {
        close(provider, probe);
        log.info("circuit_breaker_success provider={}", provider);
    }

    private void close(String provider, Probe probe) {
        Instant now = clock.instant();
        String key = stateKey(provider);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("providerId", provider);
        fields.put("state", STATE_CLOSED);
        fields.put("failureCount", "0");
        fields.put("lastSuccessAt", now.toString());
        fields.put("updatedAt", now.toString());

        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) {
                operations.opsForHash().putAll(key, fields);
                operations.opsForHash().delete(key, "openedAt", "halfOpenAt", "reason");
                operations.expire(key, properties.getStateTtl());
                if (probe.present()) {
                    operations.delete(probeKey(provider));
                }
                return null;
            }
        });
    }

    private void recordFailure(String provider, Probe probe, RuntimeException ex) {
        String key = stateKey(provider);
        long failureCount = incrementFailureCount(key);
        Instant now = clock.instant();
        boolean shouldOpen = probe.present() || failureCount >= properties.getFailureThreshold();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("providerId", provider);
        fields.put("state", shouldOpen ? STATE_OPEN : STATE_CLOSED);
        fields.put("failureCount", String.valueOf(failureCount));
        fields.put("lastFailureAt", now.toString());
        fields.put("updatedAt", now.toString());
        fields.put("reason", failureReason(ex));
        if (shouldOpen) {
            fields.put("openedAt", now.toString());
        }

        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) {
                operations.opsForHash().putAll(key, fields);
                if (shouldOpen) {
                    operations.opsForHash().delete(key, "halfOpenAt");
                }
                operations.expire(key, properties.getStateTtl());
                if (probe.present()) {
                    operations.delete(probeKey(provider));
                }
                return null;
            }
        });

        if (shouldOpen) {
            log.info("circuit_breaker_opened provider={} failures={} reason={}", provider, failureCount, fields.get("reason"));
        } else {
            log.info("circuit_breaker_failure provider={} failures={}", provider, failureCount);
        }
    }

    private long incrementFailureCount(String key) {
        Long failureCount = redisTemplate.opsForHash().increment(key, "failureCount", 1);
        return failureCount == null ? 1L : failureCount;
    }

    private void recordBlockedCall(String provider) {
        String key = stateKey(provider);
        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) {
                operations.opsForHash().increment(key, "blockedCallCount", 1);
                operations.expire(key, properties.getStateTtl());
                return null;
            }
        });
    }

    private Map<Object, Object> fields(String provider) {
        HashOperations<String, Object, Object> hashOperations = redisTemplate.opsForHash();
        Map<Object, Object> fields = hashOperations.entries(stateKey(provider));
        return fields == null ? Map.of() : fields;
    }

    private CircuitBreakerState state(String provider, Map<Object, Object> fields) {
        String state = stringField(fields, "state", STATE_CLOSED);
        long failures = longField(fields, "failureCount");
        Instant openedAt = instantField(fields, "openedAt");
        Instant openUntil = openedAt != null ? openedAt.plus(properties.getOpenDuration()) : null;
        boolean callsAllowed = STATE_CLOSED.equals(state)
                || (STATE_OPEN.equals(state) && openUntil != null && !openUntil.isAfter(clock.instant()));
        return new CircuitBreakerState(
                provider,
                state,
                failures,
                longField(fields, "blockedCallCount"),
                openedAt,
                openUntil,
                instantField(fields, "halfOpenAt"),
                instantField(fields, "lastFailureAt"),
                instantField(fields, "lastSuccessAt"),
                stringField(fields, "reason", ""),
                callsAllowed
        );
    }

    private void writeFields(String provider, Map<String, String> fields) {
        String key = stateKey(provider);
        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) {
                operations.opsForHash().putAll(key, fields);
                operations.expire(key, properties.getStateTtl());
                return null;
            }
        });
    }

    static String stateKey(String providerId) {
        return KEY_PREFIX + normalizeProviderId(providerId);
    }

    static String probeKey(String providerId) {
        return stateKey(providerId) + PROBE_SUFFIX;
    }

    public static String normalizeProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "external-api";
        }
        return providerId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "Manually opened." : reason.trim();
    }

    private static String failureReason(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String stringField(Map<Object, Object> fields, String fieldName, String defaultValue) {
        Object value = fields.get(fieldName);
        return value == null ? defaultValue : value.toString();
    }

    private static long longField(Map<Object, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static Instant instantField(Map<Object, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record Probe(String token) {
        private static Probe none() {
            return new Probe(null);
        }

        private boolean present() {
            return token != null && !token.isBlank();
        }
    }
}
