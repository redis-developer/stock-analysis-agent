package com.redis.stockanalysisagent.workflow;

import com.redis.stockanalysisagent.chat.ChatService;
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
    private final WorkflowCheckpointService checkpointService = mock(WorkflowCheckpointService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ChatService chatService = mock(ChatService.class);
    private final WorkflowRecoveryService recoveryService = new WorkflowRecoveryService(
            workflowService,
            checkpointService,
            redisTemplate,
            chatService
    );

    @Test
    void expiredWorkflowIsClaimedAndReplayedFromCheckpoint() {
        WorkflowMetadata workflow = workflow();
        WorkflowCheckpoint checkpoint = checkpoint();
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(WorkflowService.workflowKey("workflow-1"))).thenReturn(Map.of(
                "conversationId", "alice:session-1"
        ));
        when(workflowService.expiredRunningWorkflowIds(25)).thenReturn(List.of("workflow-1"));
        when(workflowService.tryClaimExpired("workflow-1")).thenReturn(Optional.of(workflow));
        when(workflowService.renewLeaseUntilClosed(workflow)).thenReturn(WorkflowService.Lease.noop());
        when(checkpointService.latestCheckpoint("workflow-1")).thenReturn(Optional.of(checkpoint));
        when(checkpointService.originalUserMessage("workflow-1")).thenReturn(Optional.of("original question"));
        when(checkpointService.replayMessage(eq("workflow-1"), any(), eq(checkpoint))).thenReturn("replay prompt");
        when(chatService.recover(
                eq("alice"),
                eq("session-1"),
                eq("replay prompt"),
                eq("original question"),
                eq("recovery:workflow-1:2:checkpoint-1"),
                eq(10),
                eq(true),
                eq(false),
                eq("workflow-1"),
                eq("checkpoint-1")
        )).thenReturn(new ChatService.ChatTurn(
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
                WorkflowStatus.COMPLETED
        ));

        recoveryService.recoverExpiredWorkflows();

        verify(workflowService).markRecovered(workflow, "replay-workflow");
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
                "owner-1",
                now.plusSeconds(45),
                2L,
                2,
                now,
                now,
                null,
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
