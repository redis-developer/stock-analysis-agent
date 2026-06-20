package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.session.ConversationId;
import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String KIND_SYSTEM = "system";

    private final AmsChatMemoryRepository memoryRepository;
    private final ChatAnalysisService chatAnalysisService;
    private final ExternalDataCache externalDataCache;
    private final ChatProgressPublisher progressPublisher;
    private final WorkflowService workflowService;

    public ChatService(
            AmsChatMemoryRepository memoryRepository,
            ChatAnalysisService chatAnalysisService,
            ExternalDataCache externalDataCache,
            ChatProgressPublisher progressPublisher,
            WorkflowService workflowService
    ) {
        this.memoryRepository = memoryRepository;
        this.chatAnalysisService = chatAnalysisService;
        this.externalDataCache = externalDataCache;
        this.progressPublisher = progressPublisher;
        this.workflowService = workflowService;
    }

    public ChatTurn chat(
            String userId,
            String sessionId,
            String message,
            String clientRequestId,
            Integer retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled
    ) {
        String conversationId = ConversationId.of(userId, sessionId).value();
        String normalizedMessage = message == null ? "" : message.trim();
        List<ChatExecutionStep> executionSteps = new ArrayList<>();
        WorkflowMetadata workflow = null;
        try {
            workflow = startWorkflow(
                    userId,
                    sessionId,
                    conversationId,
                    clientRequestId,
                    executionSteps
            );
            WorkflowContextHolder.setWorkflowId(workflow.workflowId());
            memoryRepository.setLastRetrievedMemories(List.of());
            memoryRepository.setLastMemoryRetrievalDurationMs(null);
            long analysisStartedAt = System.nanoTime();
            progressPublisher.running(
                    "REQUEST_ANALYSIS",
                    "Analyzing request",
                    ChatProgressPublisher.KIND_SYSTEM,
                    "Preparing the stock analysis request."
            );

            ChatAnalysisService.AnalysisTurn analysisTurn;
            externalDataCache.setCachingEnabled(apiCachingEnabled);
            try {
                analysisTurn = chatAnalysisService.analyze(
                        normalizedMessage,
                        conversationId,
                        retrievedMemoriesLimit,
                        semanticCachingEnabled,
                        semanticCacheKey(userId, normalizedMessage)
                );
            } catch (RuntimeException ex) {
                progressPublisher.failed(
                        "REQUEST_ANALYSIS",
                        "Analyzing request",
                        ChatProgressPublisher.KIND_SYSTEM,
                        elapsedDurationMs(analysisStartedAt),
                        errorMessage(ex)
                );
                throw ex;
            } finally {
                externalDataCache.clearCachingEnabled();
            }
            executionSteps.addAll(analysisTurn.executionSteps());
            progressPublisher.completed(
                    "REQUEST_ANALYSIS",
                    "Analyzing request",
                    ChatProgressPublisher.KIND_SYSTEM,
                    sumDurationMs(analysisTurn.executionSteps()),
                    "Completed the stock analysis request.",
                    analysisTurn.tokenUsage()
            );

            long saveTurnStartedAt = System.nanoTime();
            progressPublisher.running(
                    "TURN_SAVE",
                    "Saving turn",
                    ChatProgressPublisher.KIND_SYSTEM,
                    "Saving the user message and assistant response."
            );
            boolean saveSucceeded = saveTurn(
                    conversationId,
                    normalizedMessage,
                    analysisTurn.response(),
                    executionSteps,
                    analysisTurn.tickers(),
                    analysisTurn.triggeredAgents()
            );
            ChatExecutionStep saveStep = systemStep(
                    "TURN_SAVE",
                    "Turn save",
                    elapsedDurationMs(saveTurnStartedAt),
                    turnSaveSummary(saveSucceeded),
                    null
            );
            executionSteps.add(saveStep);
            progressPublisher.completed(
                    saveStep.id(),
                    "Saving turn",
                    ChatProgressPublisher.KIND_SYSTEM,
                    saveStep.durationMs(),
                    saveStep.summary()
            );

            workflow = completeWorkflow(workflow, executionSteps);
            return new ChatTurn(
                    conversationId,
                    analysisTurn.response(),
                    memoryRepository.getLastRetrievedMemories(),
                    analysisTurn.fromSemanticCache(),
                    analysisTurn.fromSemanticGuardrail(),
                    analysisTurn.tokenUsage(),
                    List.copyOf(executionSteps),
                    analysisTurn.tickers(),
                    analysisTurn.triggeredAgents(),
                    workflow.workflowId(),
                    workflow.status()
            );
        } catch (RuntimeException ex) {
            failWorkflow(workflow, ex);
            throw ex;
        } finally {
            WorkflowContextHolder.clear();
        }
    }

    private boolean saveTurn(
            String conversationId,
            String message,
            String response,
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents
    ) {
        try {
            memoryRepository.saveTurn(conversationId, message, response, executionSteps, tickers, triggeredAgents);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Skipping working-memory save because chat persistence failed.", ex);
            return false;
        }
    }

    private ChatExecutionStep systemStep(
            String id,
            String label,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage
    ) {
        return new ChatExecutionStep(id, label, KIND_SYSTEM, durationMs, summary, tokenUsage);
    }

    private WorkflowMetadata startWorkflow(
            String userId,
            String sessionId,
            String conversationId,
            String clientRequestId,
            List<ChatExecutionStep> executionSteps
    ) {
        long startedAt = System.nanoTime();
        progressPublisher.running(
                "WORKFLOW_START",
                "Starting workflow",
                ChatProgressPublisher.KIND_SYSTEM,
                "Creating a workflow record for this chat request."
        );
        try {
            WorkflowMetadata workflow = workflowService.start(userId, sessionId, conversationId, clientRequestId);
            ChatExecutionStep step = systemStep(
                    "WORKFLOW_START",
                    "Workflow start",
                    elapsedDurationMs(startedAt),
                    "Created workflow " + workflow.workflowId() + ".",
                    null
            );
            executionSteps.add(step);
            progressPublisher.completed(
                    step.id(),
                    "Starting workflow",
                    ChatProgressPublisher.KIND_SYSTEM,
                    step.durationMs(),
                    step.summary()
            );
            return workflow;
        } catch (RuntimeException ex) {
            progressPublisher.failed(
                    "WORKFLOW_START",
                    "Starting workflow",
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    errorMessage(ex)
            );
            throw ex;
        }
    }

    private WorkflowMetadata completeWorkflow(WorkflowMetadata workflow, List<ChatExecutionStep> executionSteps) {
        long startedAt = System.nanoTime();
        progressPublisher.running(
                "WORKFLOW_COMPLETE",
                "Completing workflow",
                ChatProgressPublisher.KIND_SYSTEM,
                "Marking the workflow completed."
        );
        WorkflowMetadata completed = workflowService.complete(workflow);
        ChatExecutionStep step = systemStep(
                "WORKFLOW_COMPLETE",
                "Workflow complete",
                elapsedDurationMs(startedAt),
                "Marked workflow " + completed.workflowId() + " completed.",
                null
        );
        executionSteps.add(step);
        progressPublisher.completed(
                step.id(),
                "Completing workflow",
                ChatProgressPublisher.KIND_SYSTEM,
                step.durationMs(),
                step.summary()
        );
        return completed;
    }

    private void failWorkflow(WorkflowMetadata workflow, RuntimeException failure) {
        if (workflow == null || workflow.status() == WorkflowStatus.COMPLETED) {
            return;
        }

        long startedAt = System.nanoTime();
        progressPublisher.running(
                "WORKFLOW_FAILURE",
                "Failing workflow",
                ChatProgressPublisher.KIND_SYSTEM,
                "Marking the workflow failed."
        );
        try {
            WorkflowMetadata failed = workflowService.fail(workflow, failure);
            progressPublisher.failed(
                    "WORKFLOW_FAILURE",
                    "Failing workflow",
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    "Marked workflow " + failed.workflowId() + " failed."
            );
        } catch (RuntimeException ex) {
            failure.addSuppressed(ex);
        }
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private long sumDurationMs(List<ChatExecutionStep> executionSteps) {
        return executionSteps.stream()
                .mapToLong(ChatExecutionStep::durationMs)
                .sum();
    }

    private String turnSaveSummary(boolean saveSucceeded) {
        return saveSucceeded
                ? "Persisted the user message and assistant response to working memory."
                : "Skipped working-memory persistence because the save failed.";
    }

    private String semanticCacheKey(String userId, String message) {
        return "user=%s\nmessage=%s".formatted(userId, message);
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    public record ChatTurn(
            String conversationId,
            String response,
            List<String> retrievedMemories,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail,
            TokenUsageSummary tokenUsage,
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents,
            String workflowId,
            WorkflowStatus workflowStatus
    ) {
        public ChatTurn {
            retrievedMemories = retrievedMemories == null ? List.of() : List.copyOf(retrievedMemories);
            executionSteps = executionSteps == null ? List.of() : List.copyOf(executionSteps);
            tickers = tickers == null ? List.of() : List.copyOf(tickers);
            triggeredAgents = triggeredAgents == null ? List.of() : List.copyOf(triggeredAgents);
        }
    }
}
