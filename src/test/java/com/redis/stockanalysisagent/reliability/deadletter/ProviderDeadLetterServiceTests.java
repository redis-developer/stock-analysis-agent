package com.redis.stockanalysisagent.reliability.deadletter;

import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderDeadLetterServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-22T10:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearWorkflowContext() {
        WorkflowContextHolder.clear();
    }

    @Test
    void appendWritesProviderFailureToRedisStream() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        RedisOperations operations = mock(RedisOperations.class);
        when(operations.opsForStream()).thenReturn(streamOperations);
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(operations);
            return List.of();
        });
        ProviderRetryProperties properties = new ProviderRetryProperties();
        properties.setDeadLetterTtl(Duration.ofDays(3));
        ProviderDeadLetterService service = new ProviderDeadLetterService(
                redisTemplate,
                properties,
                CLOCK,
                () -> "failure-1"
        );
        WorkflowContextHolder.setWorkflowId("workflow-1");

        ProviderFailureRecord record = service.append(
                "DATA:API:tavily",
                "Tavily",
                "Tavily",
                "tavily-news-search",
                "NVDA",
                2,
                new IllegalStateException("rate limited")
        );

        assertThat(record)
                .extracting(
                        ProviderFailureRecord::failureId,
                        ProviderFailureRecord::workflowId,
                        ProviderFailureRecord::providerId,
                        ProviderFailureRecord::attempts,
                        ProviderFailureRecord::failedAt
                )
                .containsExactly("failure-1", "workflow-1", "tavily", 2, "2026-06-22T10:00:00Z");
        verify(streamOperations).add(eq(ProviderDeadLetterService.KEY), any(Map.class));
        verify(operations).expire(ProviderDeadLetterService.KEY, Duration.ofDays(3));
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    void recentReadsNewestProviderFailures() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        MapRecord<String, Object, Object> storedRecord = record(Map.of(
                "failureId", "failure-1",
                "workflowId", "workflow-1",
                "stepId", "DATA:API:tavily",
                "providerId", "tavily",
                "providerLabel", "Tavily",
                "cacheName", "tavily-news-search",
                "cacheKey", "NVDA",
                "attempts", "2",
                "reason", "rate limited",
                "failedAt", "2026-06-22T10:00:00Z"
        ));
        when(streamOperations.reverseRange(
                eq(ProviderDeadLetterService.KEY),
                any(Range.class),
                any(Limit.class)
        )).thenReturn(List.of(storedRecord));
        ProviderRetryProperties properties = new ProviderRetryProperties();
        properties.setDeadLetterReadLimit(5);
        ProviderDeadLetterService service = new ProviderDeadLetterService(
                redisTemplate,
                properties,
                CLOCK,
                () -> "ignored"
        );

        List<ProviderFailureRecord> records = service.recent();

        assertThat(records)
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.failureId()).isEqualTo("failure-1");
                    assertThat(record.providerId()).isEqualTo("tavily");
                    assertThat(record.attempts()).isEqualTo(2);
                    assertThat(record.reason()).isEqualTo("rate limited");
                });
    }

    private MapRecord<String, Object, Object> record(Map<Object, Object> values) {
        @SuppressWarnings("unchecked")
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(values);
        return record;
    }
}
