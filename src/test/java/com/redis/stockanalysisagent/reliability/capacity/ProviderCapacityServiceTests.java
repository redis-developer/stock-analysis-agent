package com.redis.stockanalysisagent.reliability.capacity;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderCapacityServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-22T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void acquiresAndReleasesPermit() {
        RedisHarness redis = redisHarness();
        when(redis.redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 1L));
        ProviderCapacityService service = service(redis, properties());

        try (ProviderCapacityService.Permit permit = service.acquire("twelve-data")) {
            assertThat(permit.activeCount()).isEqualTo(1);
            assertThat(permit.limit()).isEqualTo(2);
        }

        verify(redis.zSetOperations).remove(ProviderCapacityService.permitsKey("twelve-data"), "token");
    }

    @Test
    void recordsTimeoutWhenCapacityIsUnavailable() {
        RedisHarness redis = redisHarness();
        ProviderCapacityProperties properties = properties();
        properties.setWaitTimeout(Duration.ZERO);
        when(redis.redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 2L));
        when(redis.zSetOperations.zCard(ProviderCapacityService.permitsKey("twelve-data"))).thenReturn(2L);
        ProviderCapacityService service = service(redis, properties);

        assertThatThrownBy(() -> service.acquire("twelve-data"))
                .isInstanceOf(ProviderCapacityTimeoutException.class)
                .hasMessageContaining("Timed out waiting for provider capacity");

        verify(redis.hashOperations).increment(ProviderCapacityService.statsKey("twelve-data"), "timeoutCount", 1);
    }

    @Test
    void readsCapacityStateFromRedis() {
        RedisHarness redis = redisHarness();
        when(redis.zSetOperations.zCard(ProviderCapacityService.permitsKey("sec"))).thenReturn(1L);
        when(redis.zSetOperations.zCard(ProviderCapacityService.waitingKey("sec"))).thenReturn(2L);
        when(redis.hashOperations.get(ProviderCapacityService.statsKey("sec"), "timeoutCount")).thenReturn("3");
        ProviderCapacityService service = service(redis, properties());

        ProviderCapacityState state = service.state("sec");

        assertThat(state.providerId()).isEqualTo("sec");
        assertThat(state.limit()).isEqualTo(2);
        assertThat(state.activeCount()).isEqualTo(1);
        assertThat(state.waitingCount()).isEqualTo(2);
        assertThat(state.timeoutCount()).isEqualTo(3);
        assertThat(state.capacityAvailable()).isTrue();
    }

    private ProviderCapacityService service(RedisHarness redis, ProviderCapacityProperties properties) {
        return new ProviderCapacityService(redis.redisTemplate, properties, CLOCK, () -> "token");
    }

    private ProviderCapacityProperties properties() {
        ProviderCapacityProperties properties = new ProviderCapacityProperties();
        properties.setDefaultLimit(2);
        properties.setPermitTtl(Duration.ofSeconds(30));
        properties.setWaitTimeout(Duration.ZERO);
        properties.setRetryInterval(Duration.ZERO);
        properties.setStateTtl(Duration.ofDays(7));
        return properties;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RedisHarness redisHarness() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        return new RedisHarness(redisTemplate, zSetOperations, hashOperations);
    }

    private record RedisHarness(
            StringRedisTemplate redisTemplate,
            ZSetOperations<String, String> zSetOperations,
            HashOperations<String, Object, Object> hashOperations
    ) {
    }
}
