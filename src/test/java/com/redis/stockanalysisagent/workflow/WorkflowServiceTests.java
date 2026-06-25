package com.redis.stockanalysisagent.workflow;

import com.redis.stockanalysisagent.workflow.execution.WorkflowQueueService;
import com.redis.stockanalysisagent.workflow.execution.WorkflowLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-19T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void startCreatesRunningWorkflowWithBoundedTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PipelineCapture pipeline = capturePipeline(redisTemplate, List.of(1L, "workflow-0"));
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "workflow-1", () -> "worker-1");

        WorkflowMetadata workflow = service.start("alice", "session-1", "alice:session-1", " client-1 ");

        assertThat(workflow.workflowId()).isEqualTo("workflow-1");
        assertThat(workflow.clientRequestId()).isEqualTo("client-1");
        assertThat(workflow.previousWorkflowId()).isEqualTo("workflow-0");
        assertThat(workflow.turnIndex()).isEqualTo(2);
        assertThat(workflow.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(pipeline.fields())
                .containsEntry("workflowId", "workflow-1")
                .containsEntry("clientRequestId", "client-1")
                .containsEntry("userId", "alice")
                .containsEntry("sessionId", "session-1")
                .containsEntry("conversationId", "alice:session-1")
                .containsEntry("status", "RUNNING")
                .containsEntry("previousWorkflowId", "workflow-0")
                .containsEntry("turnIndex", "2")
                .containsEntry("attempt", "1")
                .containsEntry("createdAt", "2026-06-19T10:15:30Z")
                .containsEntry("updatedAt", "2026-06-19T10:15:30Z");
        assertThat(pipeline.fields()).doesNotContainKeys("finishedAt", "failureReason");
        assertThat(WorkflowService.sessionWorkflowsKey("session-1"))
                .isEqualTo("stock-analysis:sessions:session-1:workflows");
        assertThat(WorkflowService.userWorkflowsKey("alice"))
                .isEqualTo("stock-analysis:users:alice:workflows");
        assertThat(WorkflowService.userConversationsKey("alice"))
                .isEqualTo("stock-analysis:users:alice:conversations");
        assertThat(WorkflowService.conversationWorkflowsKey("alice:session-1"))
                .isEqualTo("stock-analysis:conversations:alice:session-1:workflows");
        assertThat(pipeline.listPushes())
                .containsExactly(
                        new ListPush("stock-analysis:sessions:session-1:workflows", "workflow-1"),
                        new ListPush("stock-analysis:users:alice:workflows", "workflow-1"),
                        new ListPush("stock-analysis:users:alice:conversations", "alice:session-1"),
                        new ListPush("stock-analysis:conversations:alice:session-1:workflows", "workflow-1")
                );
        assertThat(pipeline.streamAdds())
                .containsExactly(new StreamAdd(WorkflowQueueService.RECOVERY_STREAM_KEY, "workflow-1"));
        assertThat(pipeline.leaseSets())
                .containsExactly(new LeaseSet(WorkflowLeaseService.leaseKey("workflow-1"), "worker-1"));
        verify(redisTemplate, times(2)).executePipelined(any(SessionCallback.class));
        verify(pipeline.operations()).expire(WorkflowService.workflowKey("workflow-1"), WorkflowService.workflowTtl());
        verify(pipeline.operations()).expire(WorkflowQueueService.RECOVERY_STREAM_KEY, WorkflowService.workflowTtl());
        verify(pipeline.operations()).expire(
                WorkflowService.sessionWorkflowsKey("session-1"),
                WorkflowService.workflowTtl()
        );
        verify(pipeline.operations()).expire(
                WorkflowService.userWorkflowsKey("alice"),
                WorkflowService.workflowTtl()
        );
        verify(pipeline.operations()).expire(
                WorkflowService.userConversationsKey("alice"),
                WorkflowService.workflowTtl()
        );
        verify(pipeline.operations()).expire(
                WorkflowService.conversationWorkflowsKey("alice:session-1"),
                WorkflowService.workflowTtl()
        );
    }

    @Test
    void startReplayStoresReplayOriginInWorkflowHash() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PipelineCapture pipeline = capturePipeline(redisTemplate, List.of(1L, "workflow-0"));
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "workflow-1", () -> "worker-1");

        WorkflowMetadata workflow = service.start(
                "alice",
                "session-1",
                "alice:session-1",
                "client-1",
                "source-workflow",
                "source-checkpoint"
        );

        assertThat(workflow.workflowId()).isEqualTo("workflow-1");
        assertThat(pipeline.fields())
                .containsEntry("replayedFromWorkflowId", "source-workflow")
                .containsEntry("replayCheckpointId", "source-checkpoint");
        assertThat(pipeline.listPushes())
                .contains(new ListPush("stock-analysis:sessions:session-1:workflows", "workflow-1"));
    }

    @Test
    void completeWritesTerminalStatusWithTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PipelineCapture pipeline = capturePipeline(redisTemplate);
        when(pipeline.valueOperations().get(WorkflowLeaseService.leaseKey("workflow-1"))).thenReturn("worker-1");
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "unused", () -> "worker-1");
        WorkflowMetadata running = runningWorkflow();

        WorkflowMetadata completed = service.complete(running);

        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.finishedAt()).isEqualTo(Instant.parse("2026-06-19T10:15:30Z"));
        assertThat(pipeline.fields()).containsEntry("status", "COMPLETED");
    }

    @Test
    void failWritesFailureStatusBeforeExceptionPropagates() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PipelineCapture pipeline = capturePipeline(redisTemplate);
        when(pipeline.valueOperations().get(WorkflowLeaseService.leaseKey("workflow-1"))).thenReturn("worker-1");
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "unused", () -> "worker-1");

        WorkflowMetadata failed = service.fail(runningWorkflow(), new IllegalStateException("analysis failed"));

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("analysis failed");
        assertThat(failed.finishedAt()).isEqualTo(Instant.parse("2026-06-19T10:15:30Z"));
    }

    @Test
    void markRecoveredStoresRecoveredWorkflowIdOnOriginalWorkflow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        PipelineCapture pipeline = capturePipeline(redisTemplate);
        when(pipeline.valueOperations().get(WorkflowLeaseService.leaseKey("workflow-1"))).thenReturn("worker-1");
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "unused", () -> "worker-1");

        WorkflowMetadata recovered = service.markRecovered(runningWorkflow(), "replay-workflow");

        assertThat(recovered.status()).isEqualTo(WorkflowStatus.RECOVERED);
        assertThat(pipeline.fields())
                .containsEntry("status", "RECOVERED")
                .containsEntry(WorkflowService.RECOVERED_BY_WORKFLOW_ID, "replay-workflow");
    }

    @Test
    void recoveredByWorkflowIdReadsRecoveredWorkflowHashOnly() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(WorkflowService.workflowKey("workflow-1"))).thenReturn(Map.of(
                "status", "RECOVERED",
                WorkflowService.RECOVERED_BY_WORKFLOW_ID, "replay-workflow"
        ));
        when(hashOperations.entries(WorkflowService.workflowKey("workflow-2"))).thenReturn(Map.of(
                "status", "RUNNING",
                WorkflowService.RECOVERED_BY_WORKFLOW_ID, "replay-workflow"
        ));
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "unused", () -> "worker-1");

        assertThat(service.recoveredByWorkflowId("workflow-1")).contains("replay-workflow");
        assertThat(service.recoveredByWorkflowId("workflow-2")).isEmpty();
    }

    private WorkflowMetadata runningWorkflow() {
        return new WorkflowMetadata(
                "workflow-1",
                "client-1",
                "alice",
                "session-1",
                "alice:session-1",
                WorkflowStatus.RUNNING,
                "workflow-0",
                2,
                1,
                Instant.parse("2026-06-19T10:00:00Z"),
                Instant.parse("2026-06-19T10:00:00Z"),
                null,
                null
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PipelineCapture capturePipeline(StringRedisTemplate redisTemplate) {
        return capturePipeline(redisTemplate, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PipelineCapture capturePipeline(StringRedisTemplate redisTemplate, List<Object> firstPipelineResults) {
        RedisOperations operations = mock(RedisOperations.class);
        HashOperations hashOperations = mock(HashOperations.class);
        ListOperations listOperations = mock(ListOperations.class);
        StreamOperations streamOperations = mock(StreamOperations.class);
        ValueOperations valueOperations = mock(ValueOperations.class);
        CapturedFields capturedFields = new CapturedFields();
        List<ListPush> listPushes = new ArrayList<>();
        List<StreamAdd> streamAdds = new ArrayList<>();
        List<LeaseSet> leaseSets = new ArrayList<>();
        AtomicInteger pipelineCallCount = new AtomicInteger();
        when(operations.opsForHash()).thenReturn(hashOperations);
        when(operations.opsForList()).thenReturn(listOperations);
        when(operations.opsForStream()).thenReturn(streamOperations);
        when(operations.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            if (firstPipelineResults != null && pipelineCallCount.getAndIncrement() == 0) {
                return firstPipelineResults;
            }
            SessionCallback callback = invocation.getArgument(0);
            callback.execute(operations);
            return List.of();
        });
        doAnswer(invocation -> {
            capturedFields.fields = invocation.getArgument(1);
            return null;
        }).when(hashOperations).putAll(any(), any(Map.class));
        doAnswer(invocation -> {
            listPushes.add(new ListPush(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(listOperations).rightPush(any(), any());
        doAnswer(invocation -> {
            listPushes.add(new ListPush(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(listOperations).leftPush(any(), any());
        doAnswer(invocation -> {
            Map<String, String> fields = invocation.getArgument(1);
            streamAdds.add(new StreamAdd(invocation.getArgument(0), fields.get("workflowId")));
            return null;
        }).when(streamOperations).add(any(), any(Map.class));
        doAnswer(invocation -> {
            leaseSets.add(new LeaseSet(invocation.getArgument(0), invocation.getArgument(1)));
            return true;
        }).when(valueOperations).setIfAbsent(any(), any(), any());
        return new PipelineCapture(operations, valueOperations, capturedFields, listPushes, streamAdds, leaseSets);
    }

    private record PipelineCapture(
            RedisOperations operations,
            ValueOperations valueOperations,
            CapturedFields capturedFields,
            List<ListPush> listPushes,
            List<StreamAdd> streamAdds,
            List<LeaseSet> leaseSets
    ) {
        private Map<String, String> fields() {
            return capturedFields.fields;
        }
    }

    private record ListPush(
            String key,
            String value
    ) {
    }

    private record StreamAdd(
            String key,
            String workflowId
    ) {
    }

    private record LeaseSet(
            String key,
            String workerId
    ) {
    }

    private static final class CapturedFields {
        private Map<String, String> fields = Map.of();
    }
}
