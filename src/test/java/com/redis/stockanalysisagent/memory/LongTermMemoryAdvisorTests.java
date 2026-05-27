package com.redis.stockanalysisagent.memory;

import com.redis.agentmemory.models.longtermemory.MemoryRecordResult;
import com.redis.agentmemory.models.longtermemory.MemoryRecordResults;
import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.memory.service.AgentMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryAdvisorTests {

    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final AmsChatMemoryRepository memoryRepository = mock(AmsChatMemoryRepository.class);
    private final LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(
            agentMemoryService,
            memoryRepository,
            mock(ChatProgressPublisher.class),
            10
    );

    @Test
    void searchesLongTermMemoryForFirstTurnInNewSession() {
        MemoryRecordResult memory = new MemoryRecordResult();
        memory.setCreatedAt(Instant.parse("2026-05-26T14:45:05.929Z"));
        memory.setText("User owns 20 shares of Duolingo (DUOL).");
        when(agentMemoryService.searchLongTermMemory("What stocks do I own?", "raphael", 10))
                .thenReturn(new MemoryRecordResults(List.of(memory), 1));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("What stocks do I own?")))
                .context(ChatMemory.CONVERSATION_ID, "raphael:new-session")
                .context(LongTermMemoryAdvisor.MAX_RETRIEVED_MEMORIES, 10)
                .build();

        ChatClientRequest result = advisor.before(request, mock(AdvisorChain.class));

        verify(agentMemoryService).searchLongTermMemory("What stocks do I own?", "raphael", 10);
        verify(memoryRepository, never()).findByConversationId(anyString());
        verify(memoryRepository).setLastRetrievedMemories(List.of());
        verify(memoryRepository).setLastRetrievedMemories(List.of(
                "2026-05-26T14:45:05.929Z | User owns 20 shares of Duolingo (DUOL)."
        ));
        verify(memoryRepository).setLastMemoryRetrievalDurationMs(null);
        verify(memoryRepository).setLastMemoryRetrievalDurationMs(anyLong());
        assertThat(result.prompt().getUserMessage().getText())
                .contains("BACKGROUND_MEMORY")
                .contains("User owns 20 shares of Duolingo (DUOL).");
        assertThat(result.context().get(LongTermMemoryAdvisor.RETRIEVED_MEMORIES))
                .isEqualTo(List.of("2026-05-26T14:45:05.929Z | User owns 20 shares of Duolingo (DUOL)."));
    }

    @Test
    void skipsLongTermMemoryForAnonymousUser() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("What stocks do I own?")))
                .context(ChatMemory.CONVERSATION_ID, "anonymous:new-session")
                .build();

        ChatClientRequest result = advisor.before(request, mock(AdvisorChain.class));

        verify(agentMemoryService, never()).searchLongTermMemory(anyString(), eq("anonymous"), eq(10));
        assertThat(result).isSameAs(request);
    }
}
