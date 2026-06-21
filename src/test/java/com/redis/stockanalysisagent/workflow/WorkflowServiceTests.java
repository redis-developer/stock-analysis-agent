package com.redis.stockanalysisagent.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "workflow-1", () -> "owner-1");

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
                .containsEntry("ownerId", "owner-1")
                .containsEntry("leaseUntil", "2026-06-19T10:15:45Z")
                .containsEntry("leaseUntilEpochMs", "1781864145000")
                .containsEntry("leaseVersion", "1")
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
        assertThat(pipeline.zsetAdds())
                .containsExactly(new ZSetAdd("stock-analysis:workflows:running", "workflow-1", 1781864145000.0));
        verify(redisTemplate, times(2)).executePipelined(any(SessionCallback.class));
        verify(pipeline.operations()).expire(WorkflowService.workflowKey("workflow-1"), WorkflowService.workflowTtl());
        verify(pipeline.operations()).expire(WorkflowService.runningWorkflowsKey(), WorkflowService.workflowTtl());
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
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "workflow-1", () -> "owner-1");

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
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "unused", () -> "owner-1");
        WorkflowMetadata running = runningWorkflow();

        WorkflowMetadata completed = service.complete(running);

        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.finishedAt()).isEqualTo(Instant.parse("2026-06-19T10:15:30Z"));
        assertThat(completed.ownerId()).isEqualTo("owner-1");
        assertThat(completed.leaseVersion()).isEqualTo(1L);
    }

    @Test
    void failWritesFailureStatusBeforeExceptionPropagates() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        WorkflowService service = new WorkflowService(redisTemplate, CLOCK, () -> "unused", () -> "owner-1");

        WorkflowMetadata failed = service.fail(runningWorkflow(), new IllegalStateException("analysis failed"));

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("analysis failed");
        assertThat(failed.finishedAt()).isEqualTo(Instant.parse("2026-06-19T10:15:30Z"));
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
                "owner-1",
                Instant.parse("2026-06-19T10:15:45Z"),
                1L,
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
        ZSetOperations zSetOperations = mock(ZSetOperations.class);
        CapturedFields capturedFields = new CapturedFields();
        List<ListPush> listPushes = new ArrayList<>();
        List<ZSetAdd> zsetAdds = new ArrayList<>();
        AtomicInteger pipelineCallCount = new AtomicInteger();
        when(operations.opsForHash()).thenReturn(hashOperations);
        when(operations.opsForList()).thenReturn(listOperations);
        when(operations.opsForZSet()).thenReturn(zSetOperations);
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
            zsetAdds.add(new ZSetAdd(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
            return null;
        }).when(zSetOperations).add(any(), any(), any(Double.class));
        return new PipelineCapture(operations, capturedFields, listPushes, zsetAdds);
    }

    private record PipelineCapture(
            RedisOperations operations,
            CapturedFields capturedFields,
            List<ListPush> listPushes,
            List<ZSetAdd> zsetAdds
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

    private record ZSetAdd(
            String key,
            String value,
            Double score
    ) {
    }

    private static final class CapturedFields {
        private Map<String, String> fields = Map.of();
    }
}
