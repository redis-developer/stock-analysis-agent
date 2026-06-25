package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.AgentExecution;
import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.agent.coordinator.CoordinatorAgent;
import com.redis.stockanalysisagent.agent.coordinator.CoordinatorResponse;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
class ChatAnalysisService {

    private static final String KIND_AGENT = "agent";
    private static final String KIND_SYSTEM = "system";
    private static final String COORDINATOR = "COORDINATOR";
    private static final String SEMANTIC_GUARDRAIL = "SEMANTIC_GUARDRAIL";
    private static final String SEMANTIC_CACHE = "SEMANTIC_CACHE";
    private static final String SEMANTIC_CACHE_STORE = "SEMANTIC_CACHE_STORE";
    private static final String MEMORY_RETRIEVAL = "MEMORY_RETRIEVAL";

    private final CoordinatorAgent coordinatorAgent;
    private final AmsChatMemoryRepository memoryRepository;

    ChatAnalysisService(
            CoordinatorAgent coordinatorAgent,
            AmsChatMemoryRepository memoryRepository
    ) {
        this.coordinatorAgent = coordinatorAgent;
        this.memoryRepository = memoryRepository;
    }

    AnalysisTurn analyze(
            String request,
            String conversationId,
            Integer retrievedMemoriesLimit,
            boolean semanticCachingEnabled,
            String semanticCacheKey
    ) {
        long coordinatorStartedAt = System.nanoTime();
        CoordinatorAgent.CoordinationResult coordinationResult = coordinatorAgent.execute(
                request,
                conversationId,
                retrievedMemoriesLimit,
                semanticCachingEnabled,
                semanticCacheKey
        );
        CoordinatorResponse coordinatorResponse = coordinationResult.coordinatorResponse();
        List<AgentExecution> agentExecutions = coordinationResult.agentExecutions();
        long coordinatorTotalDurationMs = elapsedDurationMs(coordinatorStartedAt);
        List<ChatExecutionStep> executionSteps = new ArrayList<>();
        executionSteps.add(cacheStep(
                semanticCachingEnabled,
                coordinationResult.cacheHit(),
                coordinationResult.cacheDurationMs()
        ));
        executionSteps.add(guardrailStep(
                coordinationResult.cacheHit(),
                coordinationResult.guardrailHit(),
                coordinationResult.guardrailRoute(),
                coordinationResult.guardrailDurationMs()
        ));
        Long memoryRetrievalDurationMs = memoryRepository.getLastMemoryRetrievalDurationMs();
        executionSteps.add(memoryRetrievalStep(
                coordinationResult.cacheHit(),
                coordinationResult.guardrailHit(),
                memoryRepository.getLastRetrievedMemories(),
                memoryRetrievalDurationMs
        ));
        executionSteps.add(coordinatorStep(
                coordinationResult,
                coordinatorTotalDurationMs,
                durationOrZero(memoryRetrievalDurationMs),
                totalAgentDurationMs(agentExecutions)
        ));
        executionSteps.addAll(extractExecutionSteps(agentExecutions));
        if (coordinationResult.semanticCacheStored()) {
            executionSteps.add(cacheStoreStep());
        }

        return new AnalysisTurn(
                resolveCoordinatorMessage(coordinatorResponse),
                List.copyOf(executionSteps),
                coordinationResult.cacheHit(),
                coordinationResult.guardrailHit(),
                TokenUsageSummary.sum(executionSteps.stream().map(ChatExecutionStep::tokenUsage).toList()),
                resolveTickers(coordinatorResponse, agentExecutions),
                triggeredAgents(agentExecutions)
        );
    }

    private String resolveCoordinatorMessage(CoordinatorResponse coordinatorResponse) {
        if (coordinatorResponse.getResponse() != null && !coordinatorResponse.getResponse().isBlank()) {
            return coordinatorResponse.getResponse();
        }

        return "I could not complete the stock-analysis request.";
    }

    private List<ChatExecutionStep> extractExecutionSteps(List<AgentExecution> agentExecutions) {
        if (agentExecutions == null) {
            return List.of();
        }

        return agentExecutions.stream()
                .map(this::toExecutionStep)
                .toList();
    }

    private ChatExecutionStep toExecutionStep(AgentExecution agentExecution) {
        return new ChatExecutionStep(
                agentExecution.ticker() == null || agentExecution.ticker().isBlank()
                        ? agentExecution.agentType().name()
                        : agentExecution.agentType().name() + ":" + agentExecution.ticker(),
                agentExecution.ticker() == null || agentExecution.ticker().isBlank()
                        ? null
                        : agentExecution.agentType().name() + " " + agentExecution.ticker(),
                KIND_AGENT,
                agentExecution.durationMs(),
                agentExecution.summary(),
                agentExecution.tokenUsage(),
                agentExecution.loop(),
                agentExecution.dataAccesses(),
                WorkflowProgress.ACTOR_TYPE_SUB_AGENT,
                agentActorName(agentExecution)
        );
    }

    private ChatExecutionStep coordinatorStep(
            CoordinatorAgent.CoordinationResult coordinationResult,
            long totalDurationMs,
            long memoryRetrievalDurationMs,
            long agentDurationMs
    ) {
        long durationMs = coordinatorDurationMs(
                coordinationResult,
                totalDurationMs,
                memoryRetrievalDurationMs,
                agentDurationMs
        );
        if (coordinationResult.cacheHit() || coordinationResult.guardrailHit()) {
            return new ChatExecutionStep(
                    COORDINATOR,
                    "Coordinator skipped",
                    KIND_SYSTEM,
                    durationMs,
                    coordinatorSkippedSummary(coordinationResult.cacheHit()),
                    coordinationResult.tokenUsage(),
                    null,
                    List.of(),
                    WorkflowProgress.ACTOR_TYPE_COORDINATOR,
                    WorkflowProgress.ACTOR_COORDINATOR
            );
        }

        return new ChatExecutionStep(
                COORDINATOR,
                null,
                KIND_AGENT,
                durationMs,
                coordinatorSummary(coordinationResult.coordinatorResponse(), coordinationResult.agentExecutions()),
                coordinationResult.tokenUsage(),
                null,
                List.of(),
                WorkflowProgress.ACTOR_TYPE_COORDINATOR,
                WorkflowProgress.ACTOR_COORDINATOR
        );
    }

    private long coordinatorDurationMs(
            CoordinatorAgent.CoordinationResult coordinationResult,
            long totalDurationMs,
            long memoryRetrievalDurationMs,
            long agentDurationMs
    ) {
        return Math.max(
                0,
                totalDurationMs
                        - coordinationResult.cacheDurationMs()
                        - coordinationResult.guardrailDurationMs()
                        - memoryRetrievalDurationMs
                        - agentDurationMs
        );
    }

    private String coordinatorSkippedSummary(boolean cacheHit) {
        if (cacheHit) {
            return "Skipped the coordinator LLM because the semantic cache returned a response.";
        }

        return "Skipped the coordinator LLM because the semantic guardrail blocked the request.";
    }

    private ChatExecutionStep guardrailStep(
            boolean cacheHit,
            boolean guardrailHit,
            String guardrailRoute,
            long durationMs
    ) {
        return new ChatExecutionStep(
                SEMANTIC_GUARDRAIL,
                "Semantic guardrail",
                KIND_SYSTEM,
                durationMs,
                cacheHit
                        ? "Skipped the semantic guardrail because the semantic cache already returned a response."
                        : guardrailHit
                        ? "Blocked the request with the `%s` semantic guardrail before any LLM call.".formatted(
                        formatGuardrailRoute(guardrailRoute)
                )
                        : "Checked the semantic guardrail after the semantic cache and allowed the request to continue.",
                null
        );
    }

    private ChatExecutionStep memoryRetrievalStep(
            boolean cacheHit,
            boolean guardrailHit,
            List<String> memories,
            Long durationMs
    ) {
        return new ChatExecutionStep(
                MEMORY_RETRIEVAL,
                "Memory retrieval",
                KIND_SYSTEM,
                durationOrZero(durationMs),
                memoryRetrievalSummary(cacheHit, guardrailHit, memories, durationMs),
                null
        );
    }

    private String memoryRetrievalSummary(
            boolean cacheHit,
            boolean guardrailHit,
            List<String> memories,
            Long durationMs
    ) {
        if (cacheHit) {
            return "Skipped long term memory retrieval because the semantic cache returned a response.";
        }

        if (guardrailHit) {
            return "Skipped long term memory retrieval because the semantic guardrail blocked the request.";
        }

        if (durationMs == null) {
            return "Long term memory retrieval did not run for this request.";
        }

        int memoryCount = memories == null ? 0 : memories.size();
        if (memoryCount == 0) {
            return "Checked long term memory before the coordinator call and found no matching memories.";
        }

        return memoryCount == 1
                ? "Retrieved 1 long term memory before the coordinator call."
                : "Retrieved %d long term memories before the coordinator call.".formatted(memoryCount);
    }

    private ChatExecutionStep cacheStep(boolean enabled, boolean cacheHit, long durationMs) {
        return new ChatExecutionStep(
                SEMANTIC_CACHE,
                "Semantic cache",
                KIND_SYSTEM,
                durationMs,
                semanticCacheSummary(enabled, cacheHit),
                null
        );
    }

    private String semanticCacheSummary(boolean enabled, boolean cacheHit) {
        if (!enabled) {
            return "Skipped semantic cache because it is disabled for this session.";
        }

        return cacheHit
                ? "Found a reusable response in the semantic cache and returned it before any other model call."
                : "Checked the semantic cache before the coordinator call and found no reusable response.";
    }

    private ChatExecutionStep cacheStoreStep() {
        return new ChatExecutionStep(
                SEMANTIC_CACHE_STORE,
                "Semantic cache store",
                KIND_SYSTEM,
                0,
                "Stored the final coordinator answer in the semantic cache after specialist tool calls completed.",
                null
        );
    }

    private String formatGuardrailRoute(String guardrailRoute) {
        if (guardrailRoute == null || guardrailRoute.isBlank()) {
            return "blocked_route";
        }

        return guardrailRoute;
    }

    private String coordinatorSummary(CoordinatorResponse coordinatorResponse, List<AgentExecution> agentExecutions) {
        String ticker = coordinatorResponse.getResolvedTickers() == null || coordinatorResponse.getResolvedTickers().isEmpty()
                ? coordinatorResponse.getResolvedTicker()
                : String.join(", ", coordinatorResponse.getResolvedTickers());
        String reasoning = coordinatorResponse.getReasoning();
        boolean hasAgentExecutions = agentExecutions != null && !agentExecutions.isEmpty();
        String baseSummary;
        if (hasText(ticker)) {
            baseSummary = hasAgentExecutions
                    ? "Resolved %s and called specialist tools.".formatted(ticker.toUpperCase())
                    : "Resolved %s and answered directly.".formatted(ticker.toUpperCase());
        } else if (hasText(coordinatorResponse.getResponse())) {
            baseSummary = coordinatorResponse.getResponse();
        } else {
            baseSummary = "Completed the coordinator response.";
        }

        return hasText(reasoning) ? "%s Reasoning: %s".formatted(baseSummary, reasoning) : baseSummary;
    }

    private String agentActorName(AgentExecution agentExecution) {
        return agentExecution.agentType().name().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private long durationOrZero(Long durationMs) {
        return durationMs == null ? 0 : Math.max(0, durationMs);
    }

    private long totalAgentDurationMs(List<AgentExecution> agentExecutions) {
        if (agentExecutions == null || agentExecutions.isEmpty()) {
            return 0;
        }

        return agentExecutions.stream()
                .mapToLong(AgentExecution::durationMs)
                .sum();
    }

    private List<String> resolveTickers(CoordinatorResponse coordinatorResponse, List<AgentExecution> agentExecutions) {
        LinkedHashSet<String> tickers = new LinkedHashSet<>();
        if (coordinatorResponse != null) {
            addTickers(tickers, coordinatorResponse.getResolvedTickers());
            addTicker(tickers, coordinatorResponse.getResolvedTicker());
        }
        if (agentExecutions != null) {
            agentExecutions.stream()
                    .filter(execution -> execution != null)
                    .forEach(execution -> addTicker(tickers, execution.ticker()));
        }
        return List.copyOf(tickers);
    }

    private List<String> triggeredAgents(List<AgentExecution> agentExecutions) {
        if (agentExecutions == null || agentExecutions.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> agents = new LinkedHashSet<>();
        agentExecutions.stream()
                .filter(execution -> execution != null)
                .filter(execution -> execution.agentType() != null)
                .map(execution -> execution.agentType().name())
                .forEach(agents::add);
        return List.copyOf(agents);
    }

    private void addTickers(LinkedHashSet<String> target, List<String> tickers) {
        if (tickers == null) {
            return;
        }
        tickers.forEach(ticker -> addTicker(target, ticker));
    }

    private void addTicker(LinkedHashSet<String> target, String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        for (String part : ticker.split("[,\\s]+")) {
            String normalized = part.trim().toUpperCase();
            if (!normalized.isBlank()) {
                target.add(normalized);
            }
        }
    }

    record AnalysisTurn(
            String response,
            List<ChatExecutionStep> executionSteps,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail,
            TokenUsageSummary tokenUsage,
            List<String> tickers,
            List<String> triggeredAgents
    ) {
        AnalysisTurn {
            tickers = tickers == null ? List.of() : List.copyOf(tickers);
            triggeredAgents = triggeredAgents == null ? List.of() : List.copyOf(triggeredAgents);
        }
    }
}
