package com.redis.stockanalysisagent.session;

import com.redis.agentmemory.models.workingmemory.MemoryMessage;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository.StoredMemoryMessage;
import com.redis.stockanalysisagent.session.dto.ChatSessionMessage;
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
