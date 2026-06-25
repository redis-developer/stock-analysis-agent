package com.redis.stockanalysisagent.agent.coordinator;

import com.redis.stockanalysisagent.agent.AgentExecution;
import com.redis.stockanalysisagent.agent.AgentType;
import com.redis.stockanalysisagent.chat.WorkflowProgress;
import com.redis.stockanalysisagent.semanticcache.SemanticAnalysisCache;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CoordinatorAgentTests {

    @Test
    void executeAllowsCoordinatorOnlyResponse() {
        SemanticAnalysisCache semanticAnalysisCache = mock(SemanticAnalysisCache.class);
        CoordinatorResponse coordinatorResponse = new CoordinatorResponse();
        coordinatorResponse.setResponse("You own AAPL.");
        coordinatorResponse.setResolvedQuestion("What stocks do I own?");
        coordinatorResponse.setSelectedAgents(List.of());
        coordinatorResponse.setReasoning("Memory contains one owned ticker.");

        CoordinatorAgent.CoordinationResult result = coordinatorAgentReturning(
                coordinatorResponse,
                List.of(),
                semanticAnalysisCache
        )
                .execute("What stocks do I own?", "alice:session-1", 4, true, "cache-key");

        assertThat(result.coordinatorResponse().getResponse()).isEqualTo("You own AAPL.");
        assertThat(result.agentExecutions()).isEmpty();
        assertThat(result.semanticCacheStored()).isFalse();
        verifyNoInteractions(semanticAnalysisCache);
    }

    @Test
    void executeReturnsToolExecutionsAndStoresSpecialistAnswer() {
        SemanticAnalysisCache semanticAnalysisCache = mock(SemanticAnalysisCache.class);
        AgentExecution execution = new AgentExecution(
                AgentType.MARKET_DATA,
                "AAPL",
                "Fetched market data.",
                12,
                null
        );
        CoordinatorResponse coordinatorResponse = new CoordinatorResponse();
        coordinatorResponse.setResponse("AAPL is trading at 123.");
        coordinatorResponse.setResolvedTicker("AAPL");
        coordinatorResponse.setSelectedAgents(List.of(AgentType.MARKET_DATA));

        CoordinatorAgent.CoordinationResult result = coordinatorAgentReturning(
                coordinatorResponse,
                List.of(execution),
                semanticAnalysisCache
        )
                .execute("What is the current price?", "alice:session-1", 4, true, "cache-key");

        assertThat(result.coordinatorResponse().getResponse()).isEqualTo("AAPL is trading at 123.");
        assertThat(result.agentExecutions()).containsExactly(execution);
        assertThat(result.semanticCacheStored()).isTrue();
        verify(semanticAnalysisCache).storeFinalResponse("cache-key", "AAPL is trading at 123.");
    }

    @Test
    void executeSkipsSemanticCacheStoreWhenDisabled() {
        SemanticAnalysisCache semanticAnalysisCache = mock(SemanticAnalysisCache.class);
        AgentExecution execution = new AgentExecution(
                AgentType.MARKET_DATA,
                "AAPL",
                "Fetched market data.",
                12,
                null
        );
        CoordinatorResponse coordinatorResponse = new CoordinatorResponse();
        coordinatorResponse.setResponse("AAPL is trading at 123.");
        coordinatorResponse.setResolvedTicker("AAPL");
        coordinatorResponse.setSelectedAgents(List.of(AgentType.MARKET_DATA));

        CoordinatorAgent.CoordinationResult result = coordinatorAgentReturning(
                coordinatorResponse,
                List.of(execution),
                semanticAnalysisCache
        )
                .execute("What is the current price?", "alice:session-1", 4, false, "cache-key");

        assertThat(result.semanticCacheStored()).isFalse();
        verifyNoInteractions(semanticAnalysisCache);
    }

    @SuppressWarnings("unchecked")
    private CoordinatorAgent coordinatorAgentReturning(
            CoordinatorResponse coordinatorResponse,
            List<AgentExecution> agentExecutions,
            SemanticAnalysisCache semanticAnalysisCache
    ) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        CoordinatorAgentTools coordinatorAgentTools = mock(CoordinatorAgentTools.class);
        when(coordinatorAgentTools.drainExecutions()).thenReturn(agentExecutions);
        when(chatClient.prompt()
                .user(anyString())
                .advisors(any(Consumer.class))
                .call()
                .responseEntity(eq(CoordinatorResponse.class)))
                .thenReturn(new ResponseEntity<>(null, coordinatorResponse));
        return new CoordinatorAgent(
                chatClient,
                coordinatorAgentTools,
                semanticAnalysisCache,
                mock(WorkflowProgress.class)
        );
    }
}
