package com.redis.stockanalysisagent.workflow.events;

import com.redis.stockanalysisagent.chat.WorkflowProgress;
import com.redis.stockanalysisagent.chat.ChatProgressStep;
import com.redis.stockanalysisagent.chat.ChatProgressMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
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
                WorkflowProgress.KIND_AGENT,
                WorkflowProgress.STATUS_COMPLETED,
                42L,
                "Analyzed stock.",
                null,
                null,
                List.of(),
                WorkflowProgress.ACTOR_TYPE_SUB_AGENT,
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
        assertThat(WorkflowEventService.checkpointsKey("workflow-1"))
                .isEqualTo("stock-analysis:workflows:workflow-1:checkpoints");
        assertThat(pipeline.records()).hasSize(3);
        assertThat(pipeline.records().getFirst().key()).isEqualTo("stock-analysis:workflows:workflow-1:events");
        assertThat(pipeline.records().getFirst().fields())
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
        assertThat(pipeline.records().get(1).key()).isEqualTo("stock-analysis:workflows:workflow-1:checkpoints");
        assertThat(pipeline.records().get(1).fields())
                .containsEntry("workflowId", "workflow-1")
                .containsEntry("eventType", "checkpoint.created")
                .containsEntry("checkpointId", "ANALYZE:market_data:1781864130000")
                .containsEntry("sourceEventType", "tool.completed")
                .containsEntry("stepId", "ANALYZE")
                .containsEntry("actorName", "market_data")
                .containsEntry("toolName", "getMarketSnapshot")
                .containsEntry("inputPayload", "{\"ticker\":\"DUOL\"}")
                .containsEntry("outputPayload", "{\"symbol\":\"DUOL\",\"currentPrice\":125.56}");
        assertThat(pipeline.records().get(2).key()).isEqualTo("stock-analysis:workflows:workflow-1:events");
        assertThat(pipeline.records().get(2).fields())
                .containsEntry("workflowId", "workflow-1")
                .containsEntry("eventType", "checkpoint.created")
                .containsEntry("stepId", "checkpoint:ANALYZE")
                .containsEntry("status", "completed")
                .containsEntry("kind", "checkpoint")
                .containsEntry("checkpointId", "ANALYZE:market_data:1781864130000")
                .containsEntry("checkpointedStepId", "ANALYZE")
                .containsEntry("checkpointedEventType", "tool.completed")
                .containsEntry("summary", "Created checkpoint for ANALYZE.");
        verify(redisTemplate).executePipelined(any(SessionCallback.class));
        verify(pipeline.operations()).expire("stock-analysis:workflows:workflow-1:events", WorkflowService.workflowTtl());
        verify(pipeline.operations()).expire("stock-analysis:workflows:workflow-1:checkpoints", WorkflowService.workflowTtl());
    }

    @Test
    void appendRunningProgressEventDoesNotWriteCheckpoint() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PipelineCapture pipeline = capturePipeline(redisTemplate);
        WorkflowEventService service = new WorkflowEventService(redisTemplate, CLOCK);
        ChatProgressStep step = new ChatProgressStep(
                "ANALYZE",
                "Analyze stock",
                WorkflowProgress.KIND_AGENT,
                WorkflowProgress.STATUS_RUNNING,
                null,
                "Analyzing stock.",
                null,
                null,
                List.of(),
                WorkflowProgress.ACTOR_TYPE_SUB_AGENT,
                "market_data",
                ChatProgressMetadata.empty()
        );

        service.appendProgressEvent("workflow-1", step);

        assertThat(pipeline.records()).hasSize(1);
        assertThat(pipeline.records().getFirst().key()).isEqualTo("stock-analysis:workflows:workflow-1:events");
        assertThat(pipeline.records().getFirst().fields())
                .containsEntry("eventType", "progress")
                .containsEntry("status", "running")
                .doesNotContainKey("checkpointId");
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
            capturedStream.records.add(new CapturedRecord(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(streamOperations).add(any(), any(Map.class));
        return new PipelineCapture(operations, capturedStream);
    }

    private record PipelineCapture(RedisOperations operations, CapturedStream capturedStream) {
        private List<CapturedRecord> records() {
            return capturedStream.records;
        }
    }

    private record CapturedRecord(String key, Map<String, String> fields) {
    }

    private static final class CapturedStream {
        private final List<CapturedRecord> records = new java.util.ArrayList<>();
    }
}
