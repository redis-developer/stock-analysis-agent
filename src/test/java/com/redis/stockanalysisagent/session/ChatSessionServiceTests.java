package com.redis.stockanalysisagent.session;

import com.redis.agentmemory.models.workingmemory.MemoryMessage;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository.StoredMemoryMessage;
import com.redis.stockanalysisagent.session.dto.ChatSessionMessage;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSessionServiceTests {

    private final ChatMemory chatMemory = mock(ChatMemory.class);
    private final AmsChatMemoryRepository memoryRepository = mock(AmsChatMemoryRepository.class);
    private final ChatSessionService chatSessionService = new ChatSessionService(chatMemory, memoryRepository);

    @Test
    void sessionMessagesHideInternalCoordinatorPayloads() {
        when(memoryRepository.findMemoryMessagesByConversationId("alice:session-1")).thenReturn(List.of(
                message("user", "Dont you have access to my memories?", "2026-05-26T10:00:00Z"),
                message("assistant", """
                        {"conversationId":"unknown","response":"I can access stock related memory.","selectedAgents":[]}
                        """, "2026-05-26T10:00:01Z"),
                message("assistant", "I can access stock related memory.", "2026-05-26T10:00:02Z")
        ));

        List<ChatSessionMessage> messages = chatSessionService.getSessionMessages("alice", "session-1");

        assertThat(messages)
                .extracting(ChatSessionMessage::content)
                .containsExactly(
                        "Dont you have access to my memories?",
                        "I can access stock related memory."
                );
    }

    @Test
    void sessionMessagesKeepStoredTimestamps() {
        when(memoryRepository.findMemoryMessagesByConversationId("alice:session-1")).thenReturn(List.of(
                message("user", "hello", "2026-05-26T10:00:00Z"),
                message("assistant", "hi", "2026-05-26T10:00:03Z")
        ));

        List<ChatSessionMessage> messages = chatSessionService.getSessionMessages("alice", "session-1");

        assertThat(messages)
                .extracting(ChatSessionMessage::timestamp)
                .containsExactly("2026-05-26T10:00:00Z", "2026-05-26T10:00:03Z");
    }

    @Test
    void sessionMessagesIncludeStoredExecutionSteps() {
        when(memoryRepository.findStoredMessagesByConversationId("alice:session-1")).thenReturn(List.of(
                new StoredMemoryMessage(
                        "assistant",
                        "answer",
                        "2026-05-26T10:00:03Z",
                        Map.of(
                                "tokenUsage", Map.of(
                                        "promptTokens", 20,
                                        "completionTokens", 8,
                                        "totalTokens", 28
                                ),
                                "executionSteps", List.of(Map.of(
                                        "id", "SEMANTIC_CACHE",
                                        "label", "Semantic cache",
                                        "kind", "system",
                                        "durationMs", 12,
                                        "summary", "Checked cache."
                                ))
                        )
                )
        ));

        List<ChatSessionMessage> messages = chatSessionService.getSessionMessages("alice", "session-1");

        assertThat(messages)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo("assistant");
                    assertThat(message.tokenUsage().promptTokens()).isEqualTo(20);
                    assertThat(message.tokenUsage().completionTokens()).isEqualTo(8);
                    assertThat(message.tokenUsage().totalTokens()).isEqualTo(28);
                    assertThat(message.executionSteps())
                            .singleElement()
                            .satisfies(step -> {
                                assertThat(step.id()).isEqualTo("SEMANTIC_CACHE");
                                assertThat(step.label()).isEqualTo("Semantic cache");
                                assertThat(step.kind()).isEqualTo("system");
                                assertThat(step.durationMs()).isEqualTo(12);
                                assertThat(step.summary()).isEqualTo("Checked cache.");
                            });
                });
    }

    @Test
    void sessionSummaryIncludesStoredTurnMetadata() {
        when(memoryRepository.findStoredMessagesByConversationId("alice:session-1")).thenReturn(List.of(
                new StoredMemoryMessage(
                        "assistant",
                        "answer",
                        "2026-05-26T10:00:03Z",
                        Map.of(
                                "tickers", List.of("AAPL", "MSFT"),
                                "triggeredAgents", List.of("MARKET_DATA", "NEWS", "MARKET_DATA")
                        )
                ),
                new StoredMemoryMessage(
                        "assistant",
                        "second answer",
                        "2026-05-26T10:01:03Z",
                        Map.of(
                                "tickers", List.of("NVDA"),
                                "triggeredAgents", List.of("SYNTHESIS")
                        )
                )
        ));

        assertThat(chatSessionService.summarizeSessions("alice", List.of("session-1")))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.createdAt()).isEqualTo("2026-05-26T10:00:03Z");
                    assertThat(summary.metadata().tickers()).containsExactly("AAPL", "MSFT", "NVDA");
                    assertThat(summary.metadata().triggeredAgents())
                            .containsExactly("MARKET_DATA", "NEWS", "SYNTHESIS");
                });
    }

    @Test
    void sessionMetadataIncludesLatestWorkflowState() {
        WorkflowService workflowService = mock(WorkflowService.class);
        ChatSessionService service = new ChatSessionService(chatMemory, memoryRepository, workflowService);
        WorkflowMetadata workflow = new WorkflowMetadata(
                "workflow-2",
                null,
                "alice",
                "session-1",
                "alice:session-1",
                WorkflowStatus.RECOVERING,
                "workflow-1",
                2,
                "owner-1",
                null,
                1L,
                1,
                null,
                null,
                null,
                null
        );
        when(memoryRepository.findStoredMessagesByConversationId("alice:session-1")).thenReturn(List.of());
        when(workflowService.sessionWorkflowIds("session-1")).thenReturn(List.of("workflow-1", "workflow-2"));
        when(workflowService.readWorkflow("workflow-2")).thenReturn(workflow);
        when(workflowService.workflowFields("workflow-2")).thenReturn(Map.of(
                "clientRequestId", "recovery:workflow-1:1:COORDINATOR",
                WorkflowService.REPLAYED_FROM_WORKFLOW_ID, "workflow-1",
                WorkflowService.REPLAY_CHECKPOINT_ID, "COORDINATOR:coordinator:1782048311457"
        ));
        when(workflowService.workflowEvents("workflow-1", 200)).thenReturn(List.of(
                Map.of(
                        "stepId", "SEMANTIC_CACHE",
                        "kind", "system",
                        "actorType", "system",
                        "actorName", "system",
                        "status", "completed",
                        "summary", "No reusable response found in the semantic cache."
                ),
                Map.of(
                        "stepId", "COORDINATOR",
                        "kind", "agent",
                        "actorType", "coordinator",
                        "actorName", "coordinator",
                        "status", "completed",
                        "summary", "Completed after 1 specialist agent call."
                )
        ));
        when(workflowService.workflowEvents("workflow-2", 200)).thenReturn(List.of(
                Map.of(
                        "stepId", "WORKFLOW_RECOVERY",
                        "kind", "system",
                        "actorType", "system",
                        "actorName", "system",
                        "status", "running",
                        "summary", "Recovering workflow workflow-1 from Redis state."
                ),
                Map.of(
                        "stepId", "tool:runMarketDataAgent:1",
                        "kind", "tool",
                        "actorType", "coordinator",
                        "actorName", "coordinator",
                        "status", "running",
                        "toolName", "runMarketDataAgent",
                        "summary", "Calling market data."
                )
        ));

        assertThat(service.sessionMetadata("alice", "session-1"))
                .satisfies(metadata -> {
                    assertThat(metadata.latestWorkflowId()).isEqualTo("workflow-2");
                    assertThat(metadata.latestWorkflowStatus()).isEqualTo("RECOVERING");
                    assertThat(metadata.latestWorkflowMode()).isEqualTo("recovery");
                    assertThat(metadata.recoveredFromWorkflowId()).isEqualTo("workflow-1");
                    assertThat(metadata.replayCheckpointId()).isEqualTo("COORDINATOR:coordinator:1782048311457");
                    assertThat(metadata.latestWorkflowSteps())
                            .extracting("id")
                            .containsExactly(
                                    "SEMANTIC_CACHE",
                                    "COORDINATOR",
                                    "WORKFLOW_RECOVERY",
                                    "tool:runMarketDataAgent:1"
                            );
                    assertThat(metadata.latestWorkflowSteps())
                            .extracting("recovered")
                            .containsExactly(true, true, false, false);
                    assertThat(metadata.latestWorkflowSteps().get(3).label()).isEqualTo("Tool runMarketDataAgent");
                });
    }

    @Test
    void sessionMetadataMarksRecoveredStepsWhileOriginalWorkflowIsRecovering() {
        WorkflowService workflowService = mock(WorkflowService.class);
        ChatSessionService service = new ChatSessionService(chatMemory, memoryRepository, workflowService);
        WorkflowMetadata workflow = new WorkflowMetadata(
                "workflow-1",
                null,
                "alice",
                "session-1",
                "alice:session-1",
                WorkflowStatus.RECOVERING,
                null,
                1,
                "owner-1",
                null,
                2L,
                2,
                null,
                null,
                null,
                null
        );
        when(memoryRepository.findStoredMessagesByConversationId("alice:session-1")).thenReturn(List.of());
        when(workflowService.sessionWorkflowIds("session-1")).thenReturn(List.of("workflow-1"));
        when(workflowService.readWorkflow("workflow-1")).thenReturn(workflow);
        when(workflowService.workflowFields("workflow-1")).thenReturn(Map.of());
        when(workflowService.workflowEvents("workflow-1", 200)).thenReturn(List.of(
                Map.of(
                        "stepId", "MEMORY_RETRIEVAL",
                        "kind", "system",
                        "actorType", "system",
                        "actorName", "system",
                        "status", "completed",
                        "summary", "Retrieved memories."
                ),
                Map.of(
                        "stepId", "checkpoint:COORDINATOR",
                        "kind", "checkpoint",
                        "actorType", "coordinator",
                        "actorName", "coordinator",
                        "status", "completed",
                        "summary", "Created checkpoint for COORDINATOR.",
                        "checkpointId", "COORDINATOR:coordinator:1782048311457"
                )
        ));

        assertThat(service.sessionMetadata("alice", "session-1"))
                .satisfies(metadata -> {
                    assertThat(metadata.latestWorkflowId()).isEqualTo("workflow-1");
                    assertThat(metadata.latestWorkflowStatus()).isEqualTo("RECOVERING");
                    assertThat(metadata.recoveredFromWorkflowId()).isEqualTo("workflow-1");
                    assertThat(metadata.replayCheckpointId()).isEqualTo("COORDINATOR:coordinator:1782048311457");
                    assertThat(metadata.latestWorkflowSteps())
                            .extracting("recovered")
                            .containsExactly(true, true);
                });
    }

    @Test
    void sessionSummariesUseFirstMessageTimestampAsCreatedAt() {
        when(memoryRepository.findMemoryMessagesByConversationId("alice:session-1")).thenReturn(List.of(
                message("user", "hello", "2026-05-26T10:00:00Z"),
                message("assistant", "hi", "2026-05-26T10:00:03Z")
        ));

        assertThat(chatSessionService.summarizeSessions("alice", List.of("session-1")))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.sessionId()).isEqualTo("session-1");
                    assertThat(summary.createdAt()).isEqualTo("2026-05-26T10:00:00Z");
                });
    }

    private static MemoryMessage message(String role, String content, String createdAt) {
        return MemoryMessage.builder()
                .role(role)
                .content(content)
                .createdAt(Instant.parse(createdAt))
                .build();
    }
}
