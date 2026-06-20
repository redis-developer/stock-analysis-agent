package com.redis.stockanalysisagent.workflow;

import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.chat.ChatProgressStep;
import com.redis.stockanalysisagent.chat.ChatProgressMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEventServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-19T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void appendProgressEventWritesRedisStreamEntryWithTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PipelineCapture pipeline = capturePipeline(redisTemplate);
        WorkflowEventService service = new WorkflowEventService(redisTemplate, CLOCK);
        ChatProgressStep step = new ChatProgressStep(
                "ANALYZE",
                "Analyze stock",
                ChatProgressPublisher.KIND_AGENT,
                ChatProgressPublisher.STATUS_COMPLETED,
                42L,
                "Analyzed stock.",
                null,
                null,
                List.of(),
                ChatProgressPublisher.ACTOR_TYPE_SUB_AGENT,
                "market_data",
                new ChatProgressMetadata(
                        "getMarketSnapshot",
                        "input-hash",
                        42L,
                        "{\"ticker\":\"DUOL\"}",
                        "output-hash",
                        84L,
                        "{\"symbol\":\"DUOL\",\"currentPrice\":125.56}",
                        ""
                )
        );

        service.appendProgressEvent("workflow-1", step);

        assertThat(WorkflowEventService.streamKey("workflow-1"))
                .isEqualTo("stock-analysis:workflows:workflow-1:events");
        assertThat(pipeline.streamKey()).isEqualTo("stock-analysis:workflows:workflow-1:events");
        assertThat(pipeline.fields())
                .containsEntry("workflowId", "workflow-1")
                .containsEntry("eventType", "tool.completed")
                .containsEntry("stepId", "ANALYZE")
                .containsEntry("status", "completed")
                .containsEntry("kind", "agent")
                .containsEntry("actorType", "sub_agent")
                .containsEntry("actorName", "market_data")
                .containsEntry("toolName", "getMarketSnapshot")
                .containsEntry("inputHash", "input-hash")
                .containsEntry("inputBytes", "42")
                .containsEntry("inputPayload", "{\"ticker\":\"DUOL\"}")
                .containsEntry("outputHash", "output-hash")
                .containsEntry("outputBytes", "84")
                .containsEntry("outputPayload", "{\"symbol\":\"DUOL\",\"currentPrice\":125.56}")
                .containsEntry("summary", "Analyzed stock.")
                .containsEntry("durationMs", "42")
                .containsEntry("timestamp", "2026-06-19T10:15:30Z");
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
        verify(pipeline.operations()).expire("stock-analysis:workflows:workflow-1:events", WorkflowService.workflowTtl());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PipelineCapture capturePipeline(StringRedisTemplate redisTemplate) {
        RedisOperations operations = mock(RedisOperations.class);
        StreamOperations streamOperations = mock(StreamOperations.class);
        CapturedStream capturedStream = new CapturedStream();
        when(operations.opsForStream()).thenReturn(streamOperations);
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(operations);
            return List.of();
        });
        doAnswer(invocation -> {
            capturedStream.key = invocation.getArgument(0);
            capturedStream.fields = invocation.getArgument(1);
            return null;
        }).when(streamOperations).add(any(), any(Map.class));
        return new PipelineCapture(operations, capturedStream);
    }

    private record PipelineCapture(RedisOperations operations, CapturedStream capturedStream) {
        private String streamKey() {
            return capturedStream.key;
        }

        private Map<String, String> fields() {
            return capturedStream.fields;
        }
    }

    private static final class CapturedStream {
        private String key;
        private Map<String, String> fields = Map.of();
    }
}
