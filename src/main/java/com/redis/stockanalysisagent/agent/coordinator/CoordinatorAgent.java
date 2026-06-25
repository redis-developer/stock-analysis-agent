package com.redis.stockanalysisagent.agent.coordinator;

import com.redis.stockanalysisagent.agent.AgentExecution;
import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.chat.ChatProgressMetadata;
import com.redis.stockanalysisagent.chat.WorkflowProgress;
import com.redis.stockanalysisagent.memory.LongTermMemoryAdvisor;
import com.redis.stockanalysisagent.semanticcache.SemanticAnalysisCache;
import com.redis.stockanalysisagent.semanticcache.SemanticCacheAdvisor;
import com.redis.stockanalysisagent.semanticguardrail.SemanticGuardrailAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CoordinatorAgent {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorAgent.class);

    private final ChatClient coordinatorChatClient;
    private final CoordinatorAgentTools coordinatorAgentTools;
    private final SemanticAnalysisCache semanticAnalysisCache;
    private final WorkflowProgress workflowProgress;

    public CoordinatorAgent(
            @Qualifier("coordinatorChatClient") ChatClient coordinatorChatClient,
            CoordinatorAgentTools coordinatorAgentTools,
            SemanticAnalysisCache semanticAnalysisCache,
            WorkflowProgress workflowProgress
    ) {
        this.coordinatorChatClient = coordinatorChatClient;
        this.coordinatorAgentTools = coordinatorAgentTools;
        this.semanticAnalysisCache = semanticAnalysisCache;
        this.workflowProgress = workflowProgress;
    }

    public CoordinationResult execute(
            String userMessage,
            String conversationId,
            Integer retrievedMemoriesLimit,
            boolean semanticCachingEnabled,
            String semanticCacheKey
    ) {
        coordinatorAgentTools.startTrace(userMessage);
        long startedAt = System.nanoTime();
        workflowProgress.running(
                "COORDINATOR",
                "Calling coordinator",
                WorkflowProgress.KIND_AGENT,
                "Routing the request and deciding which specialist agents to run.",
                WorkflowProgress.ACTOR_TYPE_COORDINATOR,
                WorkflowProgress.ACTOR_COORDINATOR,
                ChatProgressMetadata.input(userMessage)
        );
        log.info(
                "coordinator_started conversationId={} semanticCachingEnabled={} retrievedMemoriesLimit={}",
                conversationId,
                semanticCachingEnabled,
                retrievedMemoriesLimit
        );
        try {
            ResponseEntity<ChatResponse, CoordinatorResponse> response = coordinatorChatClient
                    .prompt()
                    .user(userMessage)
                    .advisors(spec -> {
                        spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, conversationId);
                        spec.param(LongTermMemoryAdvisor.MAX_RETRIEVED_MEMORIES,
                                retrievedMemoriesLimit != null ? retrievedMemoriesLimit : LongTermMemoryAdvisor.DEFAULT_MAX_MEMORIES);
                        if (semanticCachingEnabled) {
                            spec.param(SemanticCacheAdvisor.CACHE_KEY, semanticCacheKey);
                        } else {
                            spec.param(SemanticCacheAdvisor.BYPASS_CACHE, true);
                        }
                    })
                    .call()
                    .responseEntity(CoordinatorResponse.class);

            CoordinatorResponse coordinatorResponse = response.entity();
            if (coordinatorResponse == null) {
                throw new IllegalStateException("Coordinator returned no response.");
            }

            if (!hasText(coordinatorResponse.getResponse())) {
                throw new IllegalStateException("Coordinator returned an invalid response.");
            }

            ChatResponse chatResponse = response.response();
            List<AgentExecution> agentExecutions = coordinatorAgentTools.drainExecutions();
            boolean cacheHit = isCacheHit(chatResponse);
            boolean guardrailHit = isGuardrailHit(chatResponse);
            boolean semanticCacheStored = storeSemanticCache(
                    semanticCacheKey,
                    coordinatorResponse,
                    agentExecutions,
                    semanticCachingEnabled,
                    cacheHit,
                    guardrailHit
            );

            CoordinationResult result = new CoordinationResult(
                    coordinatorResponse,
                    agentExecutions,
                    TokenUsageSummary.from(chatResponse),
                    cacheHit,
                    guardrailHit,
                    metadataString(chatResponse, SemanticGuardrailAdvisor.GUARDRAIL_ROUTE),
                    metadataLong(chatResponse, SemanticCacheAdvisor.CACHE_DURATION_MS),
                    metadataLong(chatResponse, SemanticGuardrailAdvisor.GUARDRAIL_DURATION_MS),
                    semanticCacheStored
            );
            workflowProgress.completed(
                    "COORDINATOR",
                    "Calling coordinator",
                    WorkflowProgress.KIND_AGENT,
                    elapsedDurationMs(startedAt),
                    coordinatorProgressSummary(cacheHit, guardrailHit, agentExecutions),
                    result.tokenUsage(),
                    WorkflowProgress.ACTOR_TYPE_COORDINATOR,
                    WorkflowProgress.ACTOR_COORDINATOR,
                    ChatProgressMetadata.payload(
                            userMessage,
                            coordinatorOutputPayload(coordinatorResponse, agentExecutions, cacheHit, guardrailHit)
                    )
            );
            log.info(
                    "coordinator_completed conversationId={} durationMs={} specialistCalls={} cacheHit={} guardrailHit={} tokenUsage={}",
                    conversationId,
                    elapsedDurationMs(startedAt),
                    agentExecutions.size(),
                    cacheHit,
                    guardrailHit,
                    result.tokenUsage()
            );
            return result;
        } catch (RuntimeException ex) {
            log.warn(
                    "coordinator_failed conversationId={} durationMs={} error={}",
                    conversationId,
                    elapsedDurationMs(startedAt),
                    ex.getClass().getSimpleName(),
                    ex
            );
            workflowProgress.failed(
                    "COORDINATOR",
                    "Calling coordinator",
                    WorkflowProgress.KIND_AGENT,
                    elapsedDurationMs(startedAt),
                    errorMessage(ex),
                    WorkflowProgress.ACTOR_TYPE_COORDINATOR,
                    WorkflowProgress.ACTOR_COORDINATOR,
                    ChatProgressMetadata.input(userMessage)
            );
            throw ex;
        } finally {
            coordinatorAgentTools.clearTrace();
        }
    }

    private String coordinatorProgressSummary(
            boolean cacheHit,
            boolean guardrailHit,
            List<AgentExecution> agentExecutions
    ) {
        if (cacheHit) {
            return "Returned a response from the semantic cache.";
        }

        if (guardrailHit) {
            return "Stopped the request at the semantic guardrail.";
        }

        int agentCount = agentExecutions == null ? 0 : agentExecutions.size();
        if (agentCount == 0) {
            return "Completed without specialist agent calls.";
        }

        return agentCount == 1
                ? "Completed after 1 specialist agent call."
                : "Completed after %d specialist agent calls.".formatted(agentCount);
    }

    private String coordinatorOutputPayload(
            CoordinatorResponse response,
            List<AgentExecution> agentExecutions,
            boolean cacheHit,
            boolean guardrailHit
    ) {
        StringBuilder payload = new StringBuilder();
        appendLine(payload, "response", response.getResponse());
        appendLine(payload, "conversationId", response.getConversationId());
        appendLine(payload, "resolvedQuestion", response.getResolvedQuestion());
        appendLine(payload, "resolvedTicker", response.getResolvedTicker());
        if (response.getResolvedTickers() != null && !response.getResolvedTickers().isEmpty()) {
            appendLine(payload, "resolvedTickers", String.join(", ", response.getResolvedTickers()));
        }
        if (response.getSelectedAgents() != null && !response.getSelectedAgents().isEmpty()) {
            appendLine(payload, "selectedAgents", response.getSelectedAgents().toString());
        }
        appendLine(payload, "reasoning", response.getReasoning());
        appendLine(payload, "semanticCacheHit", Boolean.toString(cacheHit));
        appendLine(payload, "semanticGuardrailHit", Boolean.toString(guardrailHit));
        if (agentExecutions != null && !agentExecutions.isEmpty()) {
            payload.append("specialistResults:\n");
            for (AgentExecution execution : agentExecutions) {
                payload.append("- ")
                        .append(execution.agentType())
                        .append(execution.ticker() == null || execution.ticker().isBlank() ? "" : " " + execution.ticker())
                        .append(": ")
                        .append(execution.summary())
                        .append('\n');
            }
        }
        return payload.toString().trim();
    }

    private void appendLine(StringBuilder payload, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        payload.append(label).append(": ").append(value).append('\n');
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private boolean isCacheHit(ChatResponse chatResponse) {
        return chatResponse != null && Boolean.TRUE.equals(
                chatResponse.getMetadata().getOrDefault(SemanticCacheAdvisor.CACHE_HIT, false)
        );
    }

    private boolean isGuardrailHit(ChatResponse chatResponse) {
        return chatResponse != null && Boolean.TRUE.equals(
                chatResponse.getMetadata().getOrDefault(SemanticGuardrailAdvisor.GUARDRAIL_BLOCKED, false)
        );
    }

    private boolean storeSemanticCache(
            String semanticCacheKey,
            CoordinatorResponse coordinatorResponse,
            List<AgentExecution> agentExecutions,
            boolean semanticCachingEnabled,
            boolean cacheHit,
            boolean guardrailHit
    ) {
        if (cacheHit || guardrailHit || agentExecutions == null || agentExecutions.isEmpty()) {
            return false;
        }

        if (!semanticCachingEnabled) {
            return false;
        }

        if (semanticCacheKey == null || semanticCacheKey.isBlank()) {
            return false;
        }

        String response = coordinatorResponse.getResponse();
        if (response == null || response.isBlank()) {
            return false;
        }

        long startedAt = System.nanoTime();
        workflowProgress.running(
                "SEMANTIC_CACHE_STORE",
                "Semantic cache store",
                WorkflowProgress.KIND_SYSTEM,
                "Storing the final answer in the semantic cache."
        );
        try {
            semanticAnalysisCache.storeFinalResponse(semanticCacheKey.trim(), response.trim());
            workflowProgress.completed(
                    "SEMANTIC_CACHE_STORE",
                    "Semantic cache store",
                    WorkflowProgress.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    "Stored the final answer in the semantic cache."
            );
            return true;
        } catch (RuntimeException ex) {
            log.warn("Skipping semantic cache store because persistence failed.", ex);
            workflowProgress.failed(
                    "SEMANTIC_CACHE_STORE",
                    "Semantic cache store",
                    WorkflowProgress.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    errorMessage(ex)
            );
            return false;
        }
    }

    private String metadataString(ChatResponse chatResponse, String key) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return null;
        }
        Object value = chatResponse.getMetadata().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private long metadataLong(ChatResponse chatResponse, String key) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return 0;
        }

        Object value = chatResponse.getMetadata().get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Long.parseLong(text.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    public record CoordinationResult(
            CoordinatorResponse coordinatorResponse,
            List<AgentExecution> agentExecutions,
            TokenUsageSummary tokenUsage,
            boolean cacheHit,
            boolean guardrailHit,
            String guardrailRoute,
            long cacheDurationMs,
            long guardrailDurationMs,
            boolean semanticCacheStored
    ) {
        public CoordinationResult {
            agentExecutions = agentExecutions == null ? List.of() : List.copyOf(agentExecutions);
        }
    }
}
