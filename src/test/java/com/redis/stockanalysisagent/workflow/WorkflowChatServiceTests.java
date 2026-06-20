package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowChatServiceTests {

    private final AmsChatMemoryRepository memoryRepository = mock(AmsChatMemoryRepository.class);
    private final ChatAnalysisService chatAnalysisService = mock(ChatAnalysisService.class);
    private final ExternalDataCache externalDataCache = mock(ExternalDataCache.class);
    private final ChatProgressPublisher progressPublisher = mock(ChatProgressPublisher.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final ChatService chatService = new ChatService(
            memoryRepository,
            chatAnalysisService,
            externalDataCache,
            progressPublisher,
            workflowService
    );

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

        ChatService.ChatTurn turn = chatService.chat("alice", "session-1", " hello ", "client-1", 4, true, true);

        assertThat(turn.workflowId()).isEqualTo("workflow-1");
        assertThat(turn.workflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(turn.executionSteps()).extracting(ChatExecutionStep::id)
                .contains("WORKFLOW_START", "WORKFLOW_COMPLETE");
        assertThat(WorkflowContextHolder.workflowId()).isEmpty();
        verify(workflowService).complete(running);
    }

    @Test
    void failedChatMarksWorkflowFailedBeforePropagatingException() {
        WorkflowMetadata running = workflow(WorkflowStatus.RUNNING, null);
        RuntimeException failure = new IllegalStateException("analysis failed");
        when(workflowService.start("alice", "session-1", "alice:session-1", "client-1")).thenReturn(running);
        when(chatAnalysisService.analyze(eq("hello"), eq("alice:session-1"), eq(4), eq(true), any()))
                .thenThrow(failure);
        when(workflowService.fail(running, failure)).thenReturn(workflow(WorkflowStatus.FAILED, "analysis failed"));

        assertThatThrownBy(() -> chatService.chat("alice", "session-1", "hello", "client-1", 4, true, true))
                .isSameAs(failure);

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
                now,
                now,
                status == WorkflowStatus.RUNNING ? null : now,
                failureReason
        );
    }
}
