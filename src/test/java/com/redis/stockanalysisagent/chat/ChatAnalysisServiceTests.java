package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.AgentExecution;
import com.redis.stockanalysisagent.agent.AgentType;
import com.redis.stockanalysisagent.agent.coordinator.CoordinatorAgent;
import com.redis.stockanalysisagent.agent.coordinator.CoordinatorResponse;
import com.redis.stockanalysisagent.cache.CacheNames;
import com.redis.stockanalysisagent.cache.ExternalDataAccess;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAnalysisServiceTests {

    private final CoordinatorAgent coordinatorAgent = mock(CoordinatorAgent.class);
    private final AmsChatMemoryRepository memoryRepository = mock(AmsChatMemoryRepository.class);
    private final ChatAnalysisService chatAnalysisService = new ChatAnalysisService(
            coordinatorAgent,
            memoryRepository
    );

    @Test
    void completedResponseWithNoSpecialistWorkReturnsCoordinatorResponse() {
        CoordinatorResponse coordinatorResponse = new CoordinatorResponse();
        coordinatorResponse.setFinishReason(CoordinatorResponse.FinishReason.COMPLETED);
        coordinatorResponse.setFinalResponse("You own AAPL.");
        coordinatorResponse.setResolvedQuestion("What stocks do I own?");
        coordinatorResponse.setSelectedAgents(List.of());
        coordinatorResponse.setReasoning("Memory contains one owned ticker.");
        CoordinatorAgent.CoordinationResult coordinationResult = new CoordinatorAgent.CoordinationResult(
                coordinatorResponse,
                List.of(),
                null,
                false,
                false,
                null,
                0,
                0,
                false
        );
        when(coordinatorAgent.execute("What stocks do I own?", "alice:session-1", 4, true, "cache-key"))
                .thenReturn(coordinationResult);
        when(memoryRepository.getLastRetrievedMemories())
                .thenReturn(List.of("2026-05-26T14:45:05.929Z | User owns AAPL."));
        when(memoryRepository.getLastMemoryRetrievalDurationMs())
                .thenReturn(12L);

        ChatAnalysisService.AnalysisTurn analysisTurn = chatAnalysisService.analyze(
                "What stocks do I own?",
                "alice:session-1",
                4,
                true,
                "cache-key"
        );

        assertThat(analysisTurn.response()).isEqualTo("You own AAPL.");
        assertThat(analysisTurn.executionSteps())
                .extracting(ChatExecutionStep::id)
                .containsExactly("SEMANTIC_CACHE", "SEMANTIC_GUARDRAIL", "MEMORY_RETRIEVAL", "COORDINATOR");
        assertThat(analysisTurn.executionSteps())
                .filteredOn(step -> "MEMORY_RETRIEVAL".equals(step.id()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.durationMs()).isEqualTo(12);
                    assertThat(step.summary()).isEqualTo("Retrieved 1 long term memory before the coordinator call.");
        });
    }

    @Test
    void includesExternalDataAccessesOnSpecialistExecutionSteps() {
        CoordinatorResponse coordinatorResponse = new CoordinatorResponse();
        coordinatorResponse.setFinishReason(CoordinatorResponse.FinishReason.COMPLETED);
        coordinatorResponse.setFinalResponse("AAPL is trading at 123.");
        coordinatorResponse.setResolvedTicker("AAPL");
        coordinatorResponse.setResolvedQuestion("What is the current price?");
        coordinatorResponse.setSelectedAgents(List.of(AgentType.MARKET_DATA));
        ExternalDataAccess access = new ExternalDataAccess(
                CacheNames.MARKET_DATA_QUOTES,
                "AAPL",
                ExternalDataAccess.SOURCE_CACHE,
                4
        );
        CoordinatorAgent.CoordinationResult coordinationResult = new CoordinatorAgent.CoordinationResult(
                coordinatorResponse,
                List.of(new AgentExecution(
                        AgentType.MARKET_DATA,
                        "AAPL",
                        "Fetched the market snapshot.",
                        15,
                        null,
                        List.of(access)
                )),
                null,
                false,
                false,
                null,
                0,
                0,
                true
        );
        when(coordinatorAgent.execute("What is the current price?", "alice:session-1", 4, true, "cache-key"))
                .thenReturn(coordinationResult);
        when(memoryRepository.getLastRetrievedMemories()).thenReturn(List.of());
        when(memoryRepository.getLastMemoryRetrievalDurationMs()).thenReturn(1L);

        ChatAnalysisService.AnalysisTurn analysisTurn = chatAnalysisService.analyze(
                "What is the current price?",
                "alice:session-1",
                4,
                true,
                "cache-key"
        );

        assertThat(analysisTurn.response()).isEqualTo("AAPL is trading at 123.");
        assertThat(analysisTurn.executionSteps())
                .filteredOn(step -> "MARKET_DATA:AAPL".equals(step.id()))
                .singleElement()
                .satisfies(step -> assertThat(step.dataAccesses()).containsExactly(access));
        assertThat(analysisTurn.executionSteps())
                .extracting(ChatExecutionStep::id)
                .contains("SEMANTIC_CACHE_STORE");
        assertThat(analysisTurn.tickers()).containsExactly("AAPL");
        assertThat(analysisTurn.triggeredAgents()).containsExactly("MARKET_DATA");
    }

    @Test
    void capturesResolvedTickersAndTriggeredAgents() {
        CoordinatorResponse coordinatorResponse = new CoordinatorResponse();
        coordinatorResponse.setFinishReason(CoordinatorResponse.FinishReason.COMPLETED);
        coordinatorResponse.setFinalResponse("AAPL and MSFT were analyzed.");
        coordinatorResponse.setResolvedTickers(List.of("AAPL", "MSFT"));
        coordinatorResponse.setSelectedAgents(List.of(AgentType.MARKET_DATA, AgentType.NEWS, AgentType.SYNTHESIS));
        CoordinatorAgent.CoordinationResult coordinationResult = new CoordinatorAgent.CoordinationResult(
                coordinatorResponse,
                List.of(
                        new AgentExecution(AgentType.MARKET_DATA, "AAPL", "Fetched AAPL.", 10, null),
                        new AgentExecution(AgentType.NEWS, "MSFT", "Fetched MSFT news.", 11, null),
                        new AgentExecution(AgentType.SYNTHESIS, "AAPL,MSFT", "Synthesized comparison.", 12, null)
                ),
                null,
                false,
                false,
                null,
                0,
                0,
                false
        );
        when(coordinatorAgent.execute("Compare AAPL and MSFT", "alice:session-1", 4, true, "cache-key"))
                .thenReturn(coordinationResult);

        ChatAnalysisService.AnalysisTurn analysisTurn = chatAnalysisService.analyze(
                "Compare AAPL and MSFT",
                "alice:session-1",
                4,
                true,
                "cache-key"
        );

        assertThat(analysisTurn.tickers()).containsExactly("AAPL", "MSFT");
        assertThat(analysisTurn.triggeredAgents()).containsExactly("MARKET_DATA", "NEWS", "SYNTHESIS");
    }

    @Test
    void semanticCacheStepShowsDisabledPreference() {
        CoordinatorResponse coordinatorResponse = new CoordinatorResponse();
        coordinatorResponse.setFinishReason(CoordinatorResponse.FinishReason.COMPLETED);
        coordinatorResponse.setFinalResponse("AAPL is trading at 123.");
        CoordinatorAgent.CoordinationResult coordinationResult = new CoordinatorAgent.CoordinationResult(
                coordinatorResponse,
                List.of(),
                null,
                false,
                false,
                null,
                0,
                0,
                false
        );
        when(coordinatorAgent.execute("What is the current price?", "alice:session-1", 4, false, "cache-key"))
                .thenReturn(coordinationResult);

        ChatAnalysisService.AnalysisTurn analysisTurn = chatAnalysisService.analyze(
                "What is the current price?",
                "alice:session-1",
                4,
                false,
                "cache-key"
        );

        assertThat(analysisTurn.executionSteps())
                .filteredOn(step -> "SEMANTIC_CACHE".equals(step.id()))
                .singleElement()
                .satisfies(step -> assertThat(step.summary())
                        .isEqualTo("Skipped semantic cache because it is disabled for this session."));
    }
}
