package com.redis.stockanalysisagent.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowCheckpointServiceTests {

    @Test
    void replayMessageIncludesRecoveredEvidenceBeforeCheckpoint() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        List<MapRecord<String, Object, Object>> records = List.of(
                record(Map.of(
                        "stepId", "MARKET_DATA:NVDA",
                        "status", "completed",
                        "kind", "agent",
                        "actorType", "sub_agent",
                        "actorName", "market_data",
                        "inputPayload", "MARKET_DATA NVDA",
                        "outputPayload", "NVDA price is 210.69."
                )),
                record(Map.of(
                        "stepId", "tool:getTechnicalAnalysisSnapshot:6",
                        "status", "completed",
                        "kind", "system",
                        "actorType", "technical_analysis",
                        "actorName", "technical_analysis",
                        "toolName", "getTechnicalAnalysisSnapshot",
                        "inputPayload", "{\"ticker\":\"NVDA\"}",
                        "outputPayload", "{\"rsi\":63.2}"
                )),
                record(Map.of(
                        "stepId", "checkpoint:tool:getTechnicalAnalysisSnapshot:6",
                        "status", "completed",
                        "kind", "checkpoint",
                        "checkpointId", "tool:getTechnicalAnalysisSnapshot:6:technical_analysis:1782064704030"
                )),
                record(Map.of(
                        "stepId", "NEWS:NVDA",
                        "status", "completed",
                        "kind", "agent",
                        "actorType", "sub_agent",
                        "actorName", "news",
                        "outputPayload", "This should not be included."
                ))
        );
        when(streamOperations.range(eq(WorkflowEventService.streamKey("workflow-1")), any(Range.class)))
                .thenReturn(records);
        WorkflowCheckpointService service = new WorkflowCheckpointService(redisTemplate);

        String message = service.replayMessage(
                "workflow-1",
                Map.of("conversationId", "alice:session-1"),
                checkpoint()
        );

        assertThat(message)
                .contains("Recovered evidence")
                .contains("Specialist evidence: market_data")
                .contains("NVDA price is 210.69.")
                .contains("Tool evidence: getTechnicalAnalysisSnapshot")
                .contains("{\"rsi\":63.2}")
                .contains("Do not call specialist tools for evidence that is already present")
                .doesNotContain("This should not be included.");
    }

    private WorkflowCheckpoint checkpoint() {
        return new WorkflowCheckpoint(
                "1-0",
                Instant.parse("2026-06-19T10:15:40Z"),
                "tool:getTechnicalAnalysisSnapshot:6:technical_analysis:1782064704030",
                "tool:getTechnicalAnalysisSnapshot:6",
                "tool.completed",
                "technical_analysis",
                "technical_analysis",
                "Completed technical snapshot.",
                "17",
                "{\"ticker\":\"NVDA\"}",
                "18",
                "{\"rsi\":63.2}"
        );
    }

    private MapRecord<String, Object, Object> record(Map<Object, Object> values) {
        @SuppressWarnings("unchecked")
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(values);
        return record;
    }
}
