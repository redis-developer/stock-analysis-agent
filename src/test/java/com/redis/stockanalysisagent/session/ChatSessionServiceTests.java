package com.redis.stockanalysisagent.session;

import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.session.dto.ChatSessionMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSessionServiceTests {

    private final ChatMemory chatMemory = mock(ChatMemory.class);
    private final AmsChatMemoryRepository memoryRepository = mock(AmsChatMemoryRepository.class);
    private final ChatSessionService chatSessionService = new ChatSessionService(chatMemory, memoryRepository);

    @Test
    void sessionMessagesHideInternalCoordinatorPayloads() {
        when(memoryRepository.findByConversationId("alice:session-1")).thenReturn(List.of(
                new UserMessage("Dont you have access to my memories?"),
                new AssistantMessage("""
                        {"conversationId":"unknown","finalResponse":"I can access stock related memory.","finishReason":"DIRECT_RESPONSE","selectedAgents":[]}
                        """),
                new AssistantMessage("I can access stock related memory.")
        ));

        List<ChatSessionMessage> messages = chatSessionService.getSessionMessages("alice", "session-1");

        assertThat(messages)
                .extracting(ChatSessionMessage::content)
                .containsExactly(
                        "Dont you have access to my memories?",
                        "I can access stock related memory."
                );
    }
}
