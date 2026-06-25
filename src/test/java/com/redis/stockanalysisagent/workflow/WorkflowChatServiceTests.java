package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.session.ChatSessionIndexService;
import com.redis.stockanalysisagent.session.WorkflowStepProjector;
import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import com.redis.stockanalysisagent.workflow.events.WorkflowEventService;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.approval.WorkflowApprovalService;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowChatServiceTests {

    private final AmsChatMemoryRepository memoryRepository = mock(AmsChatMemoryRepository.class);
    private final ChatAnalysisService chatAnalysisService = mock(ChatAnalysisService.class);
    private final ExternalDataCache externalDataCache = mock(ExternalDataCache.class);
    private final WorkflowProgress workflowProgress = mock(WorkflowProgress.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowEventService workflowEventService = mock(WorkflowEventService.class);
    private final WorkflowStepProjector workflowStepProjector = new WorkflowStepProjector(workflowEventService);
    private final ChatSessionIndexService sessionIndexService = mock(ChatSessionIndexService.class);
    private final WorkflowApprovalService approvalService = mock(WorkflowApprovalService.class);
    private final ChatService chatService = new ChatService(
            memoryRepository,
            chatAnalysisService,
            externalDataCache,
            workflowProgress,
            workflowService,
            workflowStepProjector,
            sessionIndexService,
            approvalService
    );

    @BeforeEach
    void setUp() {
        when(approvalService.pendingApprovalForWorkflow(any())).thenReturn(Optional.empty());
    }

    @Test
    void successfulChatCompletesWorkflowAndClearsContext() {
        WorkflowMetadata running = workflow(WorkflowStatus.RUNNING, null);
        WorkflowMetadata completed = workflow(WorkflowStatus.COMPLETED, null);
        when(workflowService.start("alice", "session-1", "alice:session-1", "client-1")).thenReturn(running);
        when(chatAnalysisService.analyze(eq("hello"), eq("alice:session-1"), eq(4), eq(true), any()))
                .thenAnswer(invocation -> {
                    assertThat(WorkflowContextHolder.workflowId()).contains("workflow-1");
                    return new ChatAnalysisService.AnalysisTurn(
                            "response",
                            List.of(),
                            false,
                            false,
                            null,
                            List.of(),
                            List.of()
                    );
                });
        when(memoryRepository.getLastRetrievedMemories()).thenReturn(List.of());
        when(workflowService.complete(running)).thenReturn(completed);

        ChatService.ChatTurn turn = chatService.run(new ChatService.ChatRunRequest(
                "alice",
                "session-1",
                " hello ",
                "client-1",
                4,
                true,
                true,
                List.of(),
                "chat",
                null,
                null,
                true,
                null
        ));

        assertThat(turn.workflowId()).isEqualTo("workflow-1");
        assertThat(turn.workflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(turn.executionSteps()).extracting(ChatExecutionStep::id)
                .contains("WORKFLOW_START", "WORKFLOW_COMPLETE");
        assertThat(WorkflowContextHolder.workflowId()).isEmpty();
        verify(sessionIndexService).recordSessionStarted("alice", "session-1");
        verify(sessionIndexService).recordSessionCompleted("alice", "session-1");
        verify(workflowService).complete(running);
    }

    @Test
    void replayStartsWorkflowWithReplayOrigin() {
        WorkflowMetadata running = workflow(WorkflowStatus.RUNNING, null);
        WorkflowMetadata completed = workflow(WorkflowStatus.COMPLETED, null);
        when(workflowService.start(
                "alice",
                "session-1",
                "alice:session-1",
                "replay-client",
                "source-workflow",
                "source-checkpoint"
        )).thenReturn(running);
        when(chatAnalysisService.analyze(eq("replay message"), eq("alice:session-1"), eq(4), eq(false), any()))
                .thenReturn(new ChatAnalysisService.AnalysisTurn(
                        "response",
                        List.of(),
                        false,
                        false,
                        null,
                        List.of(),
                        List.of()
                ));
        when(workflowEventService.events("source-workflow", 200)).thenReturn(List.of(
                Map.of(
                        "stepId", "COORDINATOR",
                        "kind", "agent",
                        "actorType", "coordinator",
                        "actorName", "coordinator",
                        "status", "completed",
                        "summary", "Completed coordinator work.",
                        "checkpointId", "source-checkpoint"
                )
        ));
        when(memoryRepository.getLastRetrievedMemories()).thenReturn(List.of());
        when(workflowService.complete(running)).thenReturn(completed);

        ChatService.ChatTurn turn = chatService.run(new ChatService.ChatRunRequest(
                "alice",
                "session-1",
                " replay message ",
                "replay-client",
                4,
                true,
                false,
                List.of(),
                "replay",
                "source-workflow",
                "source-checkpoint",
                true,
                "original user question"
        ));

        assertThat(turn.workflowId()).isEqualTo("workflow-1");
        assertThat(turn.workflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(turn.executionSteps())
                .extracting(ChatExecutionStep::summary)
                .contains(
                        "Created replay workflow workflow-1 from source-workflow at checkpoint source-checkpoint.",
                        "Persisted the user message and assistant response to working memory."
                );
        verify(memoryRepository).saveTurn(
                eq("alice:session-1"),
                eq("original user question"),
                eq("response"),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                isNull(),
                eq("workflow-1")
        );
        ArgumentCaptor<ChatSessionMetadata> metadata = ArgumentCaptor.forClass(ChatSessionMetadata.class);
        verify(workflowProgress).workflow(metadata.capture());
        assertThat(metadata.getValue().latestWorkflowId()).isEqualTo("workflow-1");
        assertThat(metadata.getValue().recoveredFromWorkflowId()).isEqualTo("source-workflow");
        assertThat(metadata.getValue().latestWorkflowSteps())
                .extracting("id", "recovered")
                .containsExactly(tuple("COORDINATOR", true));
        verify(workflowService).start(
                "alice",
                "session-1",
                "alice:session-1",
                "replay-client",
                "source-workflow",
                "source-checkpoint"
        );
    }

    @Test
    void recoverySavesRecoveredAnswerUnderOriginalUserMessage() {
        WorkflowMetadata running = workflow(WorkflowStatus.RUNNING, null);
        WorkflowMetadata completed = workflow(WorkflowStatus.COMPLETED, null);
        when(workflowService.start(
                "alice",
                "session-1",
                "alice:session-1",
                "recovery-client",
                "source-workflow",
                "source-checkpoint"
        )).thenReturn(running);
        when(chatAnalysisService.analyze(eq("internal replay prompt"), eq("alice:session-1"), eq(4), eq(false), any()))
                .thenReturn(new ChatAnalysisService.AnalysisTurn(
                        "recovered response",
                        List.of(),
                        false,
                        false,
                        null,
                        List.of(),
                        List.of()
                ));
        when(memoryRepository.getLastRetrievedMemories()).thenReturn(List.of());
        when(workflowService.complete(running)).thenReturn(completed);

        ChatService.ChatTurn turn = chatService.run(new ChatService.ChatRunRequest(
                "alice",
                "session-1",
                " internal replay prompt ",
                "recovery-client",
                4,
                true,
                false,
                List.of(),
                "recovery",
                "source-workflow",
                "source-checkpoint",
                true,
                "original user question"
        ));

        assertThat(turn.workflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        verify(memoryRepository).saveTurn(
                eq("alice:session-1"),
                eq("original user question"),
                eq("recovered response"),
                any(),
                any(),
                any(),
                anyBoolean(),
                anyBoolean(),
                any(),
                isNull(),
                eq("workflow-1")
        );
    }

    @Test
    void failedChatMarksWorkflowFailedBeforePropagatingException() {
        WorkflowMetadata running = workflow(WorkflowStatus.RUNNING, null);
        RuntimeException failure = new IllegalStateException("analysis failed");
        when(workflowService.start("alice", "session-1", "alice:session-1", "client-1")).thenReturn(running);
        when(chatAnalysisService.analyze(eq("hello"), eq("alice:session-1"), eq(4), eq(true), any()))
                .thenThrow(failure);
        when(workflowService.fail(running, failure)).thenReturn(workflow(WorkflowStatus.FAILED, "analysis failed"));

        assertThatThrownBy(() -> chatService.run(new ChatService.ChatRunRequest(
                "alice",
                "session-1",
                "hello",
                "client-1",
                4,
                true,
                true,
                List.of(),
                "chat",
                null,
                null,
                true,
                null
        )))
                .isSameAs(failure);

        verify(sessionIndexService).recordSessionStarted("alice", "session-1");
        verify(sessionIndexService, never()).recordSessionCompleted("alice", "session-1");
        verify(workflowService).fail(running, failure);
        assertThat(WorkflowContextHolder.workflowId()).isEmpty();
    }

    private WorkflowMetadata workflow(WorkflowStatus status, String failureReason) {
        Instant now = Instant.parse("2026-06-19T10:15:30Z");
        return new WorkflowMetadata(
                "workflow-1",
                "client-1",
                "alice",
                "session-1",
                "alice:session-1",
                status,
                "workflow-0",
                2,
                1,
                now,
                now,
                status == WorkflowStatus.RUNNING ? null : now,
                failureReason
        );
    }
}
