package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.session.ChatSessionIndexService;
import com.redis.stockanalysisagent.session.ConversationId;
import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;
import com.redis.stockanalysisagent.session.dto.ChatSessionWorkflowStep;
import com.redis.stockanalysisagent.workflow.ApprovalRequiredException;
import com.redis.stockanalysisagent.workflow.ToolApproval;
import com.redis.stockanalysisagent.workflow.WorkflowApprovalService;
import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ChatSessionIndexService sessionIndexService;
    private final WorkflowApprovalService approvalService;

    public ChatService(
            AmsChatMemoryRepository memoryRepository,
            ChatAnalysisService chatAnalysisService,
            ExternalDataCache externalDataCache,
            ChatProgressPublisher progressPublisher,
            WorkflowService workflowService
    ) {
        this(memoryRepository, chatAnalysisService, externalDataCache, progressPublisher, workflowService, null, null);
    }

    @Autowired
    public ChatService(
            AmsChatMemoryRepository memoryRepository,
            ChatAnalysisService chatAnalysisService,
            ExternalDataCache externalDataCache,
            ChatProgressPublisher progressPublisher,
            WorkflowService workflowService,
            ChatSessionIndexService sessionIndexService,
            WorkflowApprovalService approvalService
    ) {
        this.memoryRepository = memoryRepository;
        this.chatAnalysisService = chatAnalysisService;
        this.externalDataCache = externalDataCache;
        this.progressPublisher = progressPublisher;
        this.workflowService = workflowService;
        this.sessionIndexService = sessionIndexService;
        this.approvalService = approvalService;
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
                List.of()
        );
    }

    public ChatTurn chat(
            String userId,
            String sessionId,
            String message,
            String clientRequestId,
            Integer retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled,
            boolean requireApprovalEnabled
    ) {
        return chat(
                userId,
                sessionId,
                message,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                requireApprovalEnabled ? WorkflowApprovalService.approvableTools() : List.of()
        );
    }

    public ChatTurn chat(
            String userId,
            String sessionId,
            String message,
            String clientRequestId,
            Integer retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled,
            List<String> approvalRequiredTools
    ) {
        return chat(
                userId,
                sessionId,
                message,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                approvalRequiredTools,
                null,
                "chat",
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
            String replayCheckpointId,
            String originalUserMessage
    ) {
        return replay(
                userId,
                sessionId,
                message,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                List.of(),
                replayedFromWorkflowId,
                replayCheckpointId,
                originalUserMessage
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
            boolean requireApprovalEnabled,
            String replayedFromWorkflowId,
            String replayCheckpointId,
            String originalUserMessage
    ) {
        return replay(
                userId,
                sessionId,
                message,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                requireApprovalEnabled ? WorkflowApprovalService.approvableTools() : List.of(),
                replayedFromWorkflowId,
                replayCheckpointId,
                originalUserMessage
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
            List<String> approvalRequiredTools,
            String replayedFromWorkflowId,
            String replayCheckpointId,
            String originalUserMessage
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
                approvalRequiredTools,
                replayOrigin,
                "replay",
                true,
                replayVisibleMessage(replayedFromWorkflowId, replayCheckpointId, originalUserMessage)
        );
    }

    private String replayVisibleMessage(String workflowId, String checkpointId, String originalUserMessage) {
        if (originalUserMessage != null && !originalUserMessage.isBlank()) {
            return originalUserMessage.trim();
        }
        return "Replay workflow " + workflowId + " from checkpoint " + checkpointId + ".";
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
        return replay(
                userId,
                sessionId,
                message,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                replayedFromWorkflowId,
                replayCheckpointId,
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
                List.of(),
                replayOrigin,
                "recovery",
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
            List<String> approvalRequiredTools,
            ReplayOrigin replayOrigin,
            String mode,
            boolean saveTurnToMemory,
            String memoryMessageOverride
    ) {
        String conversationId = ConversationId.of(userId, sessionId).value();
        String normalizedMessage = message == null ? "" : message.trim();
        List<ChatExecutionStep> executionSteps = new ArrayList<>();
        WorkflowMetadata workflow = null;
        WorkflowService.Lease lease = null;
        try {
            recordSessionStarted(userId, sessionId);
            workflow = startWorkflow(
                    userId,
                    sessionId,
                    conversationId,
                    clientRequestId,
                    replayOrigin,
                    executionSteps
            );
            WorkflowContextHolder.setWorkflowId(
                    workflow.workflowId(),
                    replayOrigin == null ? null : replayOrigin.workflowId()
            );
            setApprovalRequiredTools(approvalRequiredTools);
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
                ApprovalRequiredException approvalRequired = approvalRequired(ex);
                if (approvalRequired != null) {
                    progressPublisher.completed(
                            "REQUEST_ANALYSIS",
                            "Analyzing request",
                            ChatProgressPublisher.KIND_SYSTEM,
                            elapsedDurationMs(analysisStartedAt),
                            "Waiting for approval before continuing.",
                            ChatProgressPublisher.ACTOR_TYPE_SYSTEM,
                            ChatProgressPublisher.ACTOR_SYSTEM,
                            ChatProgressMetadata.input(normalizedMessage)
                    );
                    throw approvalRequired;
                }
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
            ToolApproval pendingApproval = pendingApprovalForWorkflow(workflow);
            if (pendingApproval != null) {
                progressPublisher.completed(
                        "REQUEST_ANALYSIS",
                        "Analyzing request",
                        ChatProgressPublisher.KIND_SYSTEM,
                        elapsedDurationMs(analysisStartedAt),
                        "Waiting for approval before continuing.",
                        ChatProgressPublisher.ACTOR_TYPE_SYSTEM,
                        ChatProgressPublisher.ACTOR_SYSTEM,
                        ChatProgressMetadata.input(normalizedMessage)
                );
                log.info(
                        "chat_workflow_pending_approval_detected workflowId={} approvalId={} toolName={}",
                        workflow.workflowId(),
                        pendingApproval.approvalId(),
                        pendingApproval.toolName()
                );
                throw new ApprovalRequiredException(pendingApproval);
            }
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
            recordSessionCompleted(userId, sessionId);
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
                    workflow.status(),
                    null
            );
        } catch (ApprovalRequiredException ex) {
            return handleApprovalRequired(
                    workflow,
                    conversationId,
                    normalizedMessage,
                    memoryMessageOverride,
                    saveTurnToMemory,
                    executionSteps,
                    ex.approval(),
                    mode
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
            clearRequireApproval();
            WorkflowContextHolder.clear();
        }
    }

    private void setApprovalRequiredTools(List<String> toolNames) {
        if (approvalService != null) {
            approvalService.setApprovalRequiredTools(toolNames);
        }
    }

    private void clearRequireApproval() {
        if (approvalService != null) {
            approvalService.clearRequireApproval();
        }
    }

    private ToolApproval pendingApprovalForWorkflow(WorkflowMetadata workflow) {
        if (approvalService == null || workflow == null) {
            return null;
        }
        return approvalService.pendingApprovalForWorkflow(workflow.workflowId()).orElse(null);
    }

    private ChatTurn handleApprovalRequired(
            WorkflowMetadata workflow,
            String conversationId,
            String normalizedMessage,
            String memoryMessageOverride,
            boolean saveTurnToMemory,
            List<ChatExecutionStep> executionSteps,
            ToolApproval approval,
            String mode
    ) {
        if (workflow == null) {
            throw new ApprovalRequiredException(approval);
        }

        WorkflowMetadata waiting = workflowService.waitingForApproval(workflow, approval);
        ChatExecutionStep approvalStep = systemStep(
                "APPROVAL_REQUIRED",
                "Approval required",
                0,
                "Waiting for approval before running " + approval.toolName() + ".",
                null
        );
        executionSteps.add(approvalStep);
        progressPublisher.completed(
                approvalStep.id(),
                "Approval required",
                ChatProgressPublisher.KIND_SYSTEM,
                approvalStep.durationMs(),
                approvalStep.summary(),
                ChatProgressPublisher.ACTOR_TYPE_SYSTEM,
                ChatProgressPublisher.ACTOR_SYSTEM,
                ChatProgressMetadata.input(approval.arguments())
        );

        String response = approvalResponse(approval);
        if (saveTurnToMemory) {
            String memoryMessage = memoryMessageOverride == null || memoryMessageOverride.isBlank()
                    ? normalizedMessage
                    : memoryMessageOverride.trim();
            saveTurn(
                    conversationId,
                    memoryMessage,
                    response,
                    executionSteps,
                    approval.ticker().isBlank() ? List.of() : List.of(approval.ticker()),
                    approval.agentType().isBlank() ? List.of() : List.of(approval.agentType()),
                    false,
                    false,
                    memoryRepository.getLastRetrievedMemories(),
                    approval
            );
        }
        recordSessionCompleted(waiting.userId(), waiting.sessionId());
        log.info(
                "chat_workflow_waiting_for_approval mode={} workflowId={} approvalId={} toolName={}",
                mode,
                waiting.workflowId(),
                approval.approvalId(),
                approval.toolName()
        );
        return new ChatTurn(
                conversationId,
                response,
                memoryRepository.getLastRetrievedMemories(),
                false,
                false,
                null,
                List.copyOf(executionSteps),
                approval.ticker().isBlank() ? List.of() : List.of(approval.ticker()),
                approval.agentType().isBlank() ? List.of() : List.of(approval.agentType()),
                waiting.workflowId(),
                waiting.status(),
                approval
        );
    }

    private void recordSessionStarted(String userId, String sessionId) {
        if (sessionIndexService != null) {
            sessionIndexService.recordSessionStarted(userId, sessionId);
        }
    }

    private void recordSessionCompleted(String userId, String sessionId) {
        if (sessionIndexService != null) {
            sessionIndexService.recordSessionCompleted(userId, sessionId);
        }
    }

    private void closeLease(WorkflowService.Lease lease) {
        if (lease != null) {
            lease.close();
        }
    }

    private boolean saveTurn(
            String conversationId,
            String message,
            String response,
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail,
            List<String> retrievedMemories
    ) {
        return saveTurn(
                conversationId,
                message,
                response,
                executionSteps,
                tickers,
                triggeredAgents,
                fromSemanticCache,
                fromSemanticGuardrail,
                retrievedMemories,
                null
        );
    }

    private boolean saveTurn(
            String conversationId,
            String message,
            String response,
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail,
            List<String> retrievedMemories,
            ToolApproval pendingApproval
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
                    fromSemanticGuardrail,
                    retrievedMemories,
                    pendingApproval
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
                analysisTurn.fromSemanticGuardrail(),
                memoryRepository.getLastRetrievedMemories()
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

    private String approvalResponse(ToolApproval approval) {
        String ticker = approval.ticker() == null || approval.ticker().isBlank() ? "" : " for " + approval.ticker();
        return "Approval required before running " + approval.toolName() + ticker + ".";
    }

    private ApprovalRequiredException approvalRequired(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ApprovalRequiredException approvalRequired) {
                return approvalRequired;
            }
            current = current.getCause();
        }
        return null;
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
            WorkflowStatus workflowStatus,
            ToolApproval pendingApproval
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
