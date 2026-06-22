package com.redis.stockanalysisagent.memory;

import com.redis.agentmemory.models.workingmemory.MemoryMessage;
import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.chat.ChatExecutionStep;
import com.redis.stockanalysisagent.memory.service.AgentMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmsChatMemoryRepositoryTests {

    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final AmsChatMemoryRepository repository = new AmsChatMemoryRepository(agentMemoryService);

    @Test
    void saveTurnStoresAssistantTurnMetadata() {
        when(agentMemoryService.getWorkingMemory("session-1", "alice", null)).thenReturn(null);
        ChatExecutionStep step = new ChatExecutionStep(
                "MARKET_DATA:AAPL",
                "MARKET_DATA AAPL",
                "agent",
                12,
                "Fetched market data.",
                new TokenUsageSummary(20, 8, 28)
        );
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);

        repository.saveTurn(
                "alice:session-1",
                "hello",
                "answer",
                List.of(step),
                List.of("aapl"),
                List.of("market_data"),
                true,
                false,
                List.of("User prefers concise answers.")
        );

        verify(agentMemoryService).appendMessageToWorkingMemory(
                eq("session-1"),
                argThat(message -> isAssistantMessage(message)),
                eq("alice"),
                eq("gpt-4o"),
                metadataCaptor.capture()
        );

        Map<String, Object> metadata = metadataCaptor.getValue();
        assertThat(metadata.get("fromSemanticCache")).isEqualTo(true);
        assertThat(metadata).doesNotContainKey("fromSemanticGuardrail");
        assertThat(metadata.get("tickers")).isEqualTo(List.of("AAPL"));
        assertThat(metadata.get("triggeredAgents")).isEqualTo(List.of("MARKET_DATA"));
        assertThat(metadata.get("retrievedMemories")).isEqualTo(List.of("User prefers concise answers."));
        assertThat(metadata.get("tokenUsage")).isEqualTo(Map.of(
                "promptTokens", 20,
                "completionTokens", 8,
                "totalTokens", 28
        ));
        assertThat(metadata.get("executionSteps")).asList().singleElement()
                .satisfies(rawStep -> {
                    Map<?, ?> stepMetadata = (Map<?, ?>) rawStep;
                    assertThat(stepMetadata.get("id")).isEqualTo("MARKET_DATA:AAPL");
                    assertThat(stepMetadata.get("label")).isEqualTo("MARKET_DATA AAPL");
                });
    }

    private boolean isAssistantMessage(MemoryMessage message) {
        return message != null && "assistant".equals(message.getRole());
    }
}
