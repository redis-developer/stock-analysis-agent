package com.redis.stockanalysisagent.workflow.execution;

import com.redis.stockanalysisagent.chat.ChatService;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import com.redis.stockanalysisagent.workflow.checkpoint.WorkflowCheckpoint;
import com.redis.stockanalysisagent.workflow.checkpoint.WorkflowCheckpointService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRecoveryServiceTests {

    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowQueueService workflowQueueService = mock(WorkflowQueueService.class);
    private final WorkflowCheckpointService checkpointService = mock(WorkflowCheckpointService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ChatService chatService = mock(ChatService.class);
    private final WorkflowRecoveryService recoveryService = new WorkflowRecoveryService(
            workflowService,
            workflowQueueService,
            checkpointService,
            redisTemplate,
            chatService
    );

    @Test
    void expiredWorkflowIsClaimedAndReplayedFromCheckpoint() {
        WorkflowMetadata workflow = workflow();
        WorkflowQueueService.QueuedWorkflow queuedWorkflow = queuedWorkflow();
        WorkflowCheckpoint checkpoint = checkpoint();
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(WorkflowService.workflowKey("workflow-1"))).thenReturn(Map.of(
                "conversationId", "alice:session-1"
        ));
        when(workflowQueueService.claimRecoverableWorkflows(25)).thenReturn(List.of(queuedWorkflow));
        when(workflowService.readWorkflow("workflow-1")).thenReturn(workflow);
        when(workflowService.tryClaimExpired("workflow-1")).thenReturn(Optional.of(workflow));
        when(workflowService.renewLeaseUntilClosed(workflow)).thenReturn(WorkflowService.Lease.noop());
        when(checkpointService.latestCheckpoint("workflow-1")).thenReturn(Optional.of(checkpoint));
        when(workflowService.recoveredByWorkflowId("workflow-1")).thenReturn(Optional.empty());
        when(checkpointService.originalUserMessage("workflow-1")).thenReturn(Optional.of("original question"));
        when(checkpointService.replayMessage(eq("workflow-1"), any(), eq(checkpoint))).thenReturn("replay prompt");
        when(chatService.run(eq(new ChatService.ChatRunRequest(
                "alice",
                "session-1",
                "replay prompt",
                "recovery:workflow-1:checkpoint-1",
                10,
                true,
                false,
                List.of(),
                "recovery",
                "workflow-1",
                "checkpoint-1",
                true,
                "original question"
        )))).thenReturn(new ChatService.ChatTurn(
                "alice:session-1",
                "recovered answer",
                List.of(),
                false,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                "replay-workflow",
                WorkflowStatus.COMPLETED,
                null
        ));

        recoveryService.recoverExpiredWorkflows();

        verify(workflowService).markRecovered(workflow, "replay-workflow");
        verify(workflowQueueService).ack(queuedWorkflow);
    }

    @Test
    void recoveredWorkflowIsAckedWithoutReplay() {
        WorkflowMetadata workflow = recoveredWorkflow();
        WorkflowQueueService.QueuedWorkflow queuedWorkflow = queuedWorkflow();
        when(workflowQueueService.claimRecoverableWorkflows(25)).thenReturn(List.of(queuedWorkflow));
        when(workflowService.readWorkflow("workflow-1")).thenReturn(workflow);

        recoveryService.recoverExpiredWorkflows();

        verify(workflowQueueService).ack(queuedWorkflow);
    }

    private WorkflowQueueService.QueuedWorkflow queuedWorkflow() {
        return new WorkflowQueueService.QueuedWorkflow("1-0", "workflow-1");
    }

    private WorkflowMetadata workflow() {
        Instant now = Instant.parse("2026-06-19T10:15:30Z");
        return new WorkflowMetadata(
                "workflow-1",
                "client-1",
                "alice",
                "session-1",
                "alice:session-1",
                WorkflowStatus.RECOVERING,
                null,
                1,
                2,
                now,
                now,
                null,
                null
        );
    }

    private WorkflowMetadata recoveredWorkflow() {
        Instant now = Instant.parse("2026-06-19T10:15:30Z");
        return new WorkflowMetadata(
                "workflow-1",
                "client-1",
                "alice",
                "session-1",
                "alice:session-1",
                WorkflowStatus.RECOVERED,
                null,
                1,
                2,
                now,
                now,
                now,
                null
        );
    }

    private WorkflowCheckpoint checkpoint() {
        return new WorkflowCheckpoint(
                "1-0",
                Instant.parse("2026-06-19T10:15:40Z"),
                "checkpoint-1",
                "COORDINATOR",
                "progress",
                "coordinator",
                "coordinator",
                "Completed coordinator.",
                "17",
                "original question",
                "18",
                "checkpoint output"
        );
    }
}
