package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.session.ConversationId;
import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;
import com.redis.stockanalysisagent.session.dto.ChatSessionWorkflowStep;
import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String KIND_SYSTEM = "system";
    private static final int WORKFLOW_EVENT_REPLAY_LIMIT = 200;

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
        return chat(
                userId,
                sessionId,
                message,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                null,
                true,
                null
        );
    }

    public ChatTurn replay(
            String userId,
            String sessionId,
            String message,
            String clientRequestId,
            Integer retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled,
            String replayedFromWorkflowId,
            String replayCheckpointId
    ) {
        ReplayOrigin replayOrigin = new ReplayOrigin(replayedFromWorkflowId, replayCheckpointId);
        return chat(
                userId,
                sessionId,
                message,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                replayOrigin,
                false,
                null
        );
    }

    public ChatTurn recover(
            String userId,
            String sessionId,
            String replayMessage,
            String originalUserMessage,
            String clientRequestId,
            Integer retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled,
            String replayedFromWorkflowId,
            String replayCheckpointId
    ) {
        ReplayOrigin replayOrigin = new ReplayOrigin(replayedFromWorkflowId, replayCheckpointId);
        return chat(
                userId,
                sessionId,
                replayMessage,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                replayOrigin,
                true,
                originalUserMessage
        );
    }

    private ChatTurn chat(
            String userId,
            String sessionId,
            String message,
            String clientRequestId,
            Integer retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled,
            ReplayOrigin replayOrigin,
            boolean saveTurnToMemory,
            String memoryMessageOverride
    ) {
        String conversationId = ConversationId.of(userId, sessionId).value();
        String normalizedMessage = message == null ? "" : message.trim();
        List<ChatExecutionStep> executionSteps = new ArrayList<>();
        WorkflowMetadata workflow = null;
        WorkflowService.Lease lease = null;
        String mode = executionMode(replayOrigin, saveTurnToMemory);
        try {
            workflow = startWorkflow(
                    userId,
                    sessionId,
                    conversationId,
                    clientRequestId,
                    replayOrigin,
                    executionSteps
            );
            WorkflowContextHolder.setWorkflowId(workflow.workflowId());
            publishWorkflowSnapshot(workflow, replayOrigin, mode);
            log.info(
                    "chat_workflow_started mode={} userId={} sessionId={} conversationId={} workflowId={} clientRequestId={}",
                    mode,
                    userId,
                    sessionId,
                    conversationId,
                    workflow.workflowId(),
                    clientRequestId
            );
            lease = workflowService.renewLeaseUntilClosed(workflow);
            memoryRepository.setLastRetrievedMemories(List.of());
            memoryRepository.setLastMemoryRetrievalDurationMs(null);
            long analysisStartedAt = System.nanoTime();
            progressPublisher.running(
                    "REQUEST_ANALYSIS",
                    "Analyzing request",
                    ChatProgressPublisher.KIND_SYSTEM,
                    "Preparing the stock analysis request.",
                    ChatProgressPublisher.ACTOR_TYPE_SYSTEM,
                    ChatProgressPublisher.ACTOR_SYSTEM,
                    ChatProgressMetadata.input(normalizedMessage)
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
                        errorMessage(ex),
                        ChatProgressPublisher.ACTOR_TYPE_SYSTEM,
                        ChatProgressPublisher.ACTOR_SYSTEM,
                        ChatProgressMetadata.input(normalizedMessage)
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
                    analysisTurn.tokenUsage(),
                    ChatProgressPublisher.ACTOR_TYPE_SYSTEM,
                    ChatProgressPublisher.ACTOR_SYSTEM,
                    ChatProgressMetadata.payload(normalizedMessage, analysisTurn.response())
            );

            if (saveTurnToMemory) {
                String memoryMessage = memoryMessageOverride == null || memoryMessageOverride.isBlank()
                        ? normalizedMessage
                        : memoryMessageOverride.trim();
                saveTurn(conversationId, memoryMessage, analysisTurn, executionSteps);
            } else {
                recordReplayTurnSkip(executionSteps);
            }

            workflow = completeWorkflow(workflow, executionSteps);
            log.info(
                    "chat_workflow_completed mode={} workflowId={} status={} steps={}",
                    mode,
                    workflow.workflowId(),
                    workflow.status(),
                    executionSteps.size()
            );
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
            if (workflow != null) {
                log.warn(
                        "chat_workflow_failed mode={} workflowId={} error={}",
                        mode,
                        workflow.workflowId(),
                        ex.getClass().getSimpleName()
                );
            }
            failWorkflow(workflow, ex);
            throw ex;
        } finally {
            closeLease(lease);
            WorkflowContextHolder.clear();
        }
    }

    private void closeLease(WorkflowService.Lease lease) {
        if (lease != null) {
            lease.close();
        }
    }

    private String executionMode(ReplayOrigin replayOrigin, boolean saveTurnToMemory) {
        if (replayOrigin == null) {
            return "chat";
        }
        return saveTurnToMemory ? "recovery" : "replay";
    }

    private boolean saveTurn(
            String conversationId,
            String message,
            String response,
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail
    ) {
        try {
            memoryRepository.saveTurn(
                    conversationId,
                    message,
                    response,
                    executionSteps,
                    tickers,
                    triggeredAgents,
                    fromSemanticCache,
                    fromSemanticGuardrail
            );
            return true;
        } catch (RuntimeException ex) {
            log.warn("Skipping working-memory save because chat persistence failed.", ex);
            return false;
        }
    }

    private void saveTurn(
            String conversationId,
            String normalizedMessage,
            ChatAnalysisService.AnalysisTurn analysisTurn,
            List<ChatExecutionStep> executionSteps
    ) {
        long saveTurnStartedAt = System.nanoTime();
        String turnPayload = turnPayload(normalizedMessage, analysisTurn.response());
        progressPublisher.running(
                "TURN_SAVE",
                "Saving turn",
                ChatProgressPublisher.KIND_SYSTEM,
                "Saving the user message and assistant response.",
                ChatProgressPublisher.ACTOR_TYPE_SYSTEM,
                ChatProgressPublisher.ACTOR_SYSTEM,
                ChatProgressMetadata.input(turnPayload)
        );
        boolean saveSucceeded = saveTurn(
                conversationId,
                normalizedMessage,
                analysisTurn.response(),
                executionSteps,
                analysisTurn.tickers(),
                analysisTurn.triggeredAgents(),
                analysisTurn.fromSemanticCache(),
                analysisTurn.fromSemanticGuardrail()
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
                saveStep.summary(),
                ChatProgressPublisher.ACTOR_TYPE_SYSTEM,
                ChatProgressPublisher.ACTOR_SYSTEM,
                ChatProgressMetadata.payload(turnPayload, saveStep.summary())
        );
    }

    private void recordReplayTurnSkip(List<ChatExecutionStep> executionSteps) {
        ChatExecutionStep saveStep = systemStep(
                "TURN_SAVE",
                "Turn save",
                0,
                "Skipped working-memory persistence for internal checkpoint replay.",
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
            ReplayOrigin replayOrigin,
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
            WorkflowMetadata workflow = replayOrigin == null
                    ? workflowService.start(userId, sessionId, conversationId, clientRequestId)
                    : workflowService.start(
                            userId,
                            sessionId,
                            conversationId,
                            clientRequestId,
                            replayOrigin.workflowId(),
                            replayOrigin.checkpointId()
                    );
            ChatExecutionStep step = systemStep(
                    "WORKFLOW_START",
                    "Workflow start",
                    elapsedDurationMs(startedAt),
                    startWorkflowSummary(workflow, replayOrigin),
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

    private String turnPayload(String message, String response) {
        return """
                USER_MESSAGE
                %s

                ASSISTANT_RESPONSE
                %s
                """.formatted(message, response);
    }

    private String startWorkflowSummary(WorkflowMetadata workflow, ReplayOrigin replayOrigin) {
        if (replayOrigin == null) {
            return "Created workflow " + workflow.workflowId() + ".";
        }
        return "Created replay workflow " + workflow.workflowId()
                + " from " + replayOrigin.workflowId()
                + " at checkpoint " + replayOrigin.checkpointId() + ".";
    }

    private void publishWorkflowSnapshot(WorkflowMetadata workflow, ReplayOrigin replayOrigin, String mode) {
        if (replayOrigin == null) {
            return;
        }

        progressPublisher.workflow(new ChatSessionMetadata(
                List.of(),
                List.of(),
                workflow.workflowId(),
                workflow.status().name(),
                mode,
                replayOrigin.workflowId(),
                replayOrigin.checkpointId(),
                null,
                null,
                recoveredWorkflowSteps(replayOrigin.workflowId(), replayOrigin.checkpointId())
        ));
    }

    private List<ChatSessionWorkflowStep> recoveredWorkflowSteps(String workflowId, String checkpointId) {
        if (workflowId == null || workflowId.isBlank() || checkpointId == null || checkpointId.isBlank()) {
            return List.of();
        }

        List<Map<String, String>> events = workflowService.workflowEvents(workflowId, WORKFLOW_EVENT_REPLAY_LIMIT);
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        boolean hasCheckpointEvent = events.stream()
                .anyMatch(event -> checkpointId.equals(value(event, "checkpointId")));
        String checkpointedStepId = checkpointedStepId(checkpointId);
        List<ChatSessionWorkflowStep> steps = new ArrayList<>();
        boolean foundCheckpoint = false;
        for (Map<String, String> event : events) {
            ChatSessionWorkflowStep step = workflowStep(event, true);
            if (step != null) {
                steps.add(step);
            }
            if (checkpointId.equals(value(event, "checkpointId"))
                    || (!hasCheckpointEvent && isCompletedStep(event, checkpointedStepId))) {
                foundCheckpoint = true;
                break;
            }
        }

        return foundCheckpoint ? List.copyOf(steps) : List.of();
    }

    private String checkpointedStepId(String checkpointId) {
        String value = checkpointId == null || checkpointId.isBlank() ? null : checkpointId.trim();
        if (value == null) {
            return "";
        }
        int lastSeparator = value.lastIndexOf(':');
        if (lastSeparator < 0) {
            return value;
        }
        int actorSeparator = value.lastIndexOf(':', lastSeparator - 1);
        if (actorSeparator < 0) {
            return value;
        }
        return value.substring(0, actorSeparator);
    }

    private boolean isCompletedStep(Map<String, String> event, String stepId) {
        return stepId != null
                && !stepId.isBlank()
                && stepId.equals(value(event, "stepId"))
                && "completed".equals(value(event, "status"));
    }

    private ChatSessionWorkflowStep workflowStep(Map<String, String> event, boolean recovered) {
        if (event == null || event.isEmpty()) {
            return null;
        }

        String stepId = value(event, "stepId");
        if (stepId.isBlank()) {
            return null;
        }

        String toolName = value(event, "toolName");
        return new ChatSessionWorkflowStep(
                stepId,
                workflowStepLabel(stepId, toolName),
                value(event, "kind"),
                value(event, "status"),
                longObject(value(event, "durationMs")),
                value(event, "summary"),
                value(event, "actorType"),
                value(event, "actorName"),
                recovered
        );
    }

    private String workflowStepLabel(String stepId, String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            return "Tool " + toolName;
        }
        if (stepId == null || stepId.isBlank()) {
            return "Workflow step";
        }
        if (stepId.startsWith("checkpoint:")) {
            return "Checkpoint";
        }
        return stepId.replace(':', ' ').replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private String value(Map<String, String> fields, String name) {
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    private Long longObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private record ReplayOrigin(String workflowId, String checkpointId) {
    }
}
