package com.redis.stockanalysisagent.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ProviderCapacityService {

    private static final Logger log = LoggerFactory.getLogger(ProviderCapacityService.class);
    private static final String KEY_PREFIX = "stock-analysis:provider-capacity:";
    private static final String PERMITS_SUFFIX = ":permits";
    private static final String WAITING_SUFFIX = ":waiting";
    private static final String STATS_SUFFIX = ":stats";
    private static final RedisScript<List> ACQUIRE_SCRIPT = RedisScript.of("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local active = redis.call('ZCARD', KEYS[1])
            if active < tonumber(ARGV[2]) then
              redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
              redis.call('EXPIRE', KEYS[1], ARGV[5])
              return {1, active + 1}
            end
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return {0, active}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final ProviderCapacityProperties properties;
    private final Clock clock;
    private final Supplier<String> tokenSupplier;

    @Autowired
    public ProviderCapacityService(
            StringRedisTemplate redisTemplate,
            ProviderCapacityProperties properties
    ) {
        this(redisTemplate, properties, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    ProviderCapacityService(
            StringRedisTemplate redisTemplate,
            ProviderCapacityProperties properties,
            Clock clock,
            Supplier<String> tokenSupplier
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
        this.tokenSupplier = tokenSupplier;
    }

    public Permit acquire(String providerId) {
        String provider = CircuitBreakerService.normalizeProviderId(providerId);
        if (!properties.isEnabled()) {
            return Permit.disabled(provider);
        }

        long deadlineMs = clock.instant().plus(properties.getWaitTimeout()).toEpochMilli();
        String waitToken = tokenSupplier.get();
        boolean waiting = false;
        while (true) {
            AcquireResult result = tryAcquire(provider);
            if (result.acquired()) {
                if (waiting) {
                    removeWaiting(provider, waitToken);
                }
                log.info("provider_capacity_acquired provider={} active={} limit={}",
                        provider,
                        result.activeCount(),
                        properties.getDefaultLimit());
                return new Permit(this, provider, result.token(), result.activeCount(), properties.getDefaultLimit());
            }

            if (clock.instant().toEpochMilli() >= deadlineMs) {
                if (waiting) {
                    removeWaiting(provider, waitToken);
                }
                recordTimeout(provider);
                ProviderCapacityState state = state(provider);
                log.info("provider_capacity_timeout provider={} active={} waiting={} limit={}",
                        provider,
                        state.activeCount(),
                        state.waitingCount(),
                        state.limit());
                throw new ProviderCapacityTimeoutException(provider, state);
            }

            if (!waiting) {
                addWaiting(provider, waitToken, deadlineMs);
                waiting = true;
            }
            if (!sleep()) {
                if (waiting) {
                    removeWaiting(provider, waitToken);
                }
                recordTimeout(provider);
                throw new ProviderCapacityTimeoutException(provider, state(provider));
            }
        }
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public ProviderCapacityState state(String providerId) {
        String provider = CircuitBreakerService.normalizeProviderId(providerId);
        cleanup(provider);
        long active = zCard(permitsKey(provider));
        long waiting = zCard(waitingKey(provider));
        long timeouts = timeoutCount(provider);
        int limit = properties.getDefaultLimit();
        return new ProviderCapacityState(provider, limit, active, waiting, timeouts, active < limit);
    }

    private AcquireResult tryAcquire(String provider) {
        String token = tokenSupplier.get();
        long nowMs = clock.instant().toEpochMilli();
        long expiresAtMs = clock.instant().plus(properties.getPermitTtl()).toEpochMilli();
        List<?> result = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(permitsKey(provider)),
                String.valueOf(nowMs),
                String.valueOf(properties.getDefaultLimit()),
                String.valueOf(expiresAtMs),
                token,
                String.valueOf(properties.getStateTtl().toSeconds())
        );
        boolean acquired = longResult(result, 0) == 1;
        long activeCount = longResult(result, 1);
        return new AcquireResult(acquired, activeCount, token);
    }

    private void release(String provider, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        redisTemplate.opsForZSet().remove(permitsKey(provider), token);
        log.info("provider_capacity_released provider={}", provider);
    }

    private void addWaiting(String provider, String token, long deadlineMs) {
        String key = waitingKey(provider);
        redisTemplate.opsForZSet().add(key, token, deadlineMs);
        redisTemplate.expire(key, properties.getStateTtl());
        log.info("provider_capacity_waiting provider={} limit={}", provider, properties.getDefaultLimit());
    }

    private void removeWaiting(String provider, String token) {
        redisTemplate.opsForZSet().remove(waitingKey(provider), token);
    }

    private void recordTimeout(String provider) {
        String key = statsKey(provider);
        redisTemplate.opsForHash().increment(key, "timeoutCount", 1);
        redisTemplate.expire(key, properties.getStateTtl());
    }

    private void cleanup(String provider) {
        long nowMs = Instant.now(clock).toEpochMilli();
        redisTemplate.opsForZSet().removeRangeByScore(permitsKey(provider), 0, nowMs);
        redisTemplate.opsForZSet().removeRangeByScore(waitingKey(provider), 0, nowMs);
    }

    private long zCard(String key) {
        Long count = redisTemplate.opsForZSet().zCard(key);
        return count == null ? 0 : count;
    }

    private long timeoutCount(String provider) {
        Object value = redisTemplate.opsForHash().get(statsKey(provider), "timeoutCount");
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean sleep() {
        try {
            Thread.sleep(properties.getRetryInterval().toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long longResult(List<?> result, int index) {
        if (result == null || result.size() <= index) {
            return 0;
        }
        Object value = result.get(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static String permitsKey(String providerId) {
        return KEY_PREFIX + CircuitBreakerService.normalizeProviderId(providerId) + PERMITS_SUFFIX;
    }

    static String waitingKey(String providerId) {
        return KEY_PREFIX + CircuitBreakerService.normalizeProviderId(providerId) + WAITING_SUFFIX;
    }

    static String statsKey(String providerId) {
        return KEY_PREFIX + CircuitBreakerService.normalizeProviderId(providerId) + STATS_SUFFIX;
    }

    public static final class Permit implements AutoCloseable {

        private final ProviderCapacityService service;
        private final String providerId;
        private final String token;
        private final long activeCount;
        private final int limit;

        private Permit(ProviderCapacityService service, String providerId, String token, long activeCount, int limit) {
            this.service = service;
            this.providerId = providerId;
            this.token = token;
            this.activeCount = activeCount;
            this.limit = limit;
        }

        private static Permit disabled(String providerId) {
            return new Permit(null, providerId, null, 0, 0);
        }

        public long activeCount() {
            return activeCount;
        }

        public int limit() {
            return limit;
        }

        @Override
        public void close() {
            if (service != null) {
                service.release(providerId, token);
            }
        }
    }

    private record AcquireResult(boolean acquired, long activeCount, String token) {
    }
}
