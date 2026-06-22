package com.redis.stockanalysisagent.reliability;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CircuitBreakerServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void opensProviderAfterFailureThreshold() {
        RedisHarness redis = redisHarness();
        when(redis.hashOperations.entries(CircuitBreakerService.stateKey("tavily"))).thenReturn(Map.of());
        when(redis.hashOperations.increment(CircuitBreakerService.stateKey("tavily"), "failureCount", 1))
                .thenReturn(3L);
        CircuitBreakerService service = service(redis);

        assertThatThrownBy(() -> service.call("tavily", () -> {
            throw new IllegalStateException("rate limited");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(redis.writes)
                .singleElement()
                .satisfies(write -> assertThat(write.fields())
                        .containsEntry("providerId", "tavily")
                        .containsEntry("state", "OPEN")
                        .containsEntry("failureCount", "3")
                        .containsEntry("openedAt", "2026-06-21T12:00:00Z")
                        .containsEntry("reason", "rate limited"));
    }

    @Test
    void blocksCallsWhileProviderIsOpen() {
        RedisHarness redis = redisHarness();
        when(redis.hashOperations.entries(CircuitBreakerService.stateKey("sec"))).thenReturn(Map.of(
                "providerId", "sec",
                "state", "OPEN",
                "failureCount", "3",
                "openedAt", "2026-06-21T11:59:45Z"
        ));
        CircuitBreakerService service = service(redis);
        AtomicBoolean called = new AtomicBoolean(false);

        assertThatThrownBy(() -> service.call("sec", () -> {
            called.set(true);
            return "payload";
        })).isInstanceOf(CircuitBreakerOpenException.class)
                .hasMessageContaining("Circuit breaker is open for provider sec");

        assertThat(called).isFalse();
        verify(redis.valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
        verify(redis.hashOperations).increment(CircuitBreakerService.stateKey("sec"), "blockedCallCount", 1);
    }

    @Test
    void expiredOpenProviderAllowsOneProbeAndClosesOnSuccess() {
        RedisHarness redis = redisHarness();
        when(redis.hashOperations.entries(CircuitBreakerService.stateKey("twelve-data"))).thenReturn(Map.of(
                "providerId", "twelve-data",
                "state", "OPEN",
                "failureCount", "3",
                "openedAt", "2026-06-21T11:59:00Z"
        ));
        when(redis.valueOperations.setIfAbsent(
                eq(CircuitBreakerService.probeKey("twelve-data")),
                eq("probe-token"),
                eq(Duration.ofSeconds(10))
        )).thenReturn(true);
        CircuitBreakerService service = service(redis);

        String result = service.call("twelve-data", () -> "payload");

        assertThat(result).isEqualTo("payload");
        assertThat(redis.writes).hasSize(2);
        assertThat(redis.writes.get(0).fields()).containsEntry("state", "HALF_OPEN");
        assertThat(redis.writes.get(1).fields())
                .containsEntry("state", "CLOSED")
                .containsEntry("failureCount", "0")
                .containsEntry("lastSuccessAt", "2026-06-21T12:00:00Z");
        verify(redis.operations).delete(CircuitBreakerService.probeKey("twelve-data"));
    }

    @Test
    void manualOpenWritesCircuitState() {
        RedisHarness redis = redisHarness();
        when(redis.hashOperations.entries(CircuitBreakerService.stateKey("agent-memory"))).thenReturn(Map.of(
                "providerId", "agent-memory",
                "state", "OPEN",
                "failureCount", "3",
                "openedAt", "2026-06-21T12:00:00Z",
                "reason", "demo"
        ));
        CircuitBreakerService service = service(redis);

        CircuitBreakerState state = service.open("agent-memory", "demo");

        assertThat(redis.writes)
                .singleElement()
                .satisfies(write -> assertThat(write.fields())
                        .containsEntry("providerId", "agent-memory")
                        .containsEntry("state", "OPEN")
                        .containsEntry("reason", "demo"));
        assertThat(state.providerId()).isEqualTo("agent-memory");
        verify(redis.redisTemplate).delete(CircuitBreakerService.probeKey("agent-memory"));
    }

    @Test
    void simulatedFailureOpensProviderThroughNormalFailurePath() {
        RedisHarness redis = redisHarness();
        ProviderFailureSimulationService simulator = mock(ProviderFailureSimulationService.class);
        when(redis.hashOperations.entries(CircuitBreakerService.stateKey("tavily"))).thenReturn(Map.of());
        when(redis.hashOperations.increment(CircuitBreakerService.stateKey("tavily"), "failureCount", 1))
                .thenReturn(3L);
        doThrow(new IllegalStateException("Simulated provider outage for tavily."))
                .when(simulator).throwIfEnabled("tavily");
        CircuitBreakerService service = service(redis, simulator);
        AtomicBoolean called = new AtomicBoolean(false);

        assertThatThrownBy(() -> service.call("tavily", () -> {
            called.set(true);
            return "payload";
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated provider outage");

        assertThat(called).isFalse();
        assertThat(redis.writes)
                .singleElement()
                .satisfies(write -> assertThat(write.fields())
                        .containsEntry("providerId", "tavily")
                        .containsEntry("state", "OPEN")
                        .containsEntry("reason", "Simulated provider outage for tavily."));
    }

    private CircuitBreakerService service(RedisHarness redis) {
        return service(redis, null);
    }

    private CircuitBreakerService service(RedisHarness redis, ProviderFailureSimulationService simulator) {
        CircuitBreakerProperties properties = new CircuitBreakerProperties();
        properties.setFailureThreshold(3);
        properties.setOpenDuration(Duration.ofSeconds(30));
        properties.setProbeTimeout(Duration.ofSeconds(10));
        properties.setStateTtl(Duration.ofDays(7));
        return new CircuitBreakerService(redis.redisTemplate, properties, simulator, CLOCK, () -> "probe-token");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RedisHarness redisHarness() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RedisOperations operations = mock(RedisOperations.class);
        List<Write> writes = new ArrayList<>();

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(operations.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(operations);
            return List.of();
        });
        doAnswer(invocation -> {
            writes.add(new Write(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(hashOperations).putAll(any(), any(Map.class));

        return new RedisHarness(redisTemplate, hashOperations, valueOperations, operations, writes);
    }

    private record RedisHarness(
            StringRedisTemplate redisTemplate,
            HashOperations<String, Object, Object> hashOperations,
            ValueOperations<String, String> valueOperations,
            RedisOperations operations,
            List<Write> writes
    ) {
    }

    private record Write(String key, Map<String, String> fields) {
    }
}
