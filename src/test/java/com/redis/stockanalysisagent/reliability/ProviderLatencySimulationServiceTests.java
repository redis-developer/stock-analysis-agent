package com.redis.stockanalysisagent.reliability;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderLatencySimulationServiceTests {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
    private final ProviderLatencySimulationService service = new ProviderLatencySimulationService(redisTemplate);

    @Test
    void storesDelayByProvider() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        service.setDelay("Twelve Data", Duration.ofSeconds(8));

        verify(hashOperations).put("stock-analysis:provider-latency-simulations", "twelve_data", "8000");
    }

    @Test
    void readsDelayByProvider() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("stock-analysis:provider-latency-simulations", "tavily")).thenReturn("2500");

        assertThat(service.delayMs("tavily")).isEqualTo(2500);
    }

    @Test
    void zeroDelayClearsProvider() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        service.setDelay("sec", Duration.ZERO);

        verify(hashOperations).delete("stock-analysis:provider-latency-simulations", "sec");
    }
}
