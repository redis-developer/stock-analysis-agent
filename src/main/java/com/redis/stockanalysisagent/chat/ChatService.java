package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.session.ChatSessionIndexService;
import com.redis.stockanalysisagent.session.ConversationId;
import com.redis.stockanalysisagent.session.WorkflowStepProjector;
import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;
import com.redis.stockanalysisagent.workflow.approval.ApprovalRequiredException;
import com.redis.stockanalysisagent.workflow.approval.ToolApproval;
import com.redis.stockanalysisagent.workflow.approval.WorkflowApprovalService;
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

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String KIND_SYSTEM = "system";
    private static final int WORKFLOW_EVENT_REPLAY_LIMIT = 200;

    private final AmsChatMemoryRepository memoryRepository;
    private final ChatAnalysisService chatAnalysisService;
    private final ExternalDataCache externalDataCache;
    private final WorkflowProgress workflowProgress;
    private final WorkflowService workflowService;
    private final WorkflowStepProjector workflowStepProjector;
    private final ChatSessionIndexService sessionIndexService;
    private final WorkflowApprovalService approvalService;

    @Autowired
    public ChatService(
            AmsChatMemoryRepository memoryRepository,
            ChatAnalysisService chatAnalysisService,
            ExternalDataCache externalDataCache,
            WorkflowProgress workflowProgress,
            WorkflowService workflowService,
            WorkflowStepProjector workflowStepProjector,
            ChatSessionIndexService sessionIndexService,
            WorkflowApprovalService approvalService
    ) {
        this.memoryRepository = memoryRepository;
        this.chatAnalysisService = chatAnalysisService;
        this.externalDataCache = externalDataCache;
        this.workflowProgress = workflowProgress;
        this.workflowService = workflowService;
        this.workflowStepProjector = workflowStepProjector;
        this.sessionIndexService = sessionIndexService;
        this.approvalService = approvalService;
    }

    public ChatTurn run(ChatRunRequest request) {
        ReplayOrigin replayOrigin = request.replayOrigin();
        String conversationId = ConversationId.of(request.userId(), request.sessionId()).value();
        String normalizedMessage = request.message() == null ? "" : request.message().trim();
        List<ChatExecutionStep> executionSteps = new ArrayList<>();
        WorkflowMetadata workflow = null;
        WorkflowService.Lease lease = null;
        try {
            sessionIndexService.recordSessionStarted(request.userId(), request.sessionId());
            long workflowStartedAt = System.nanoTime();
            workflowProgress.running(
                    "WORKFLOW_START",
                    "Starting workflow",
                    WorkflowProgress.KIND_SYSTEM,
                    "Creating a workflow record for this chat request."
            );
            try {
                workflow = replayOrigin == null
                        ? workflowService.start(request.userId(), request.sessionId(), conversationId, request.clientRequestId())
                        : workflowService.start(
                                request.userId(),
                                request.sessionId(),
                                conversationId,
                                request.clientRequestId(),
                                replayOrigin.workflowId(),
                                replayOrigin.checkpointId()
                        );
                ChatExecutionStep step = systemStep(
                        "WORKFLOW_START",
                        "Workflow start",
                        elapsedDurationMs(workflowStartedAt),
                        startWorkflowSummary(workflow, replayOrigin),
                        null
                );
                executionSteps.add(step);
                workflowProgress.completed(
                        step.id(),
                        "Starting workflow",
                        WorkflowProgress.KIND_SYSTEM,
                        step.durationMs(),
                        step.summary()
                );
            } catch (RuntimeException ex) {
                workflowProgress.failed(
                        "WORKFLOW_START",
                        "Starting workflow",
                        WorkflowProgress.KIND_SYSTEM,
                        elapsedDurationMs(workflowStartedAt),
                        errorMessage(ex)
                );
                throw ex;
            }
            WorkflowContextHolder.setWorkflowId(
                    workflow.workflowId(),
                    replayOrigin == null ? null : replayOrigin.workflowId()
            );
            approvalService.setApprovalRequiredTools(request.approvalRequiredTools());
            publishWorkflowSnapshot(workflow, replayOrigin, request.mode());
            log.info(
                    "chat_workflow_started mode={} userId={} sessionId={} conversationId={} workflowId={} clientRequestId={}",
                    request.mode(),
                    request.userId(),
                    request.sessionId(),
                    conversationId,
                    workflow.workflowId(),
                    request.clientRequestId()
            );
            lease = workflowService.renewLeaseUntilClosed(workflow);
            memoryRepository.setLastRetrievedMemories(List.of());
            memoryRepository.setLastMemoryRetrievalDurationMs(null);
            long analysisStartedAt = System.nanoTime();
            workflowProgress.running(
                    "REQUEST_ANALYSIS",
                    "Analyzing request",
                    WorkflowProgress.KIND_SYSTEM,
                    "Preparing the stock analysis request.",
                    WorkflowProgress.ACTOR_TYPE_SYSTEM,
                    WorkflowProgress.ACTOR_SYSTEM,
                    ChatProgressMetadata.input(normalizedMessage)
            );

            ChatAnalysisService.AnalysisTurn analysisTurn;
            externalDataCache.setCachingEnabled(request.apiCachingEnabled());
            try {
                analysisTurn = chatAnalysisService.analyze(
                        normalizedMessage,
                        conversationId,
                        request.retrievedMemoriesLimit(),
                        request.semanticCachingEnabled(),
                        semanticCacheKey(request.userId(), normalizedMessage)
                );
            } catch (RuntimeException ex) {
                ApprovalRequiredException approvalRequired = approvalRequired(ex);
                if (approvalRequired != null) {
                    workflowProgress.completed(
                            "REQUEST_ANALYSIS",
                            "Analyzing request",
                            WorkflowProgress.KIND_SYSTEM,
                            elapsedDurationMs(analysisStartedAt),
                            "Waiting for approval before continuing.",
                            WorkflowProgress.ACTOR_TYPE_SYSTEM,
                            WorkflowProgress.ACTOR_SYSTEM,
                            ChatProgressMetadata.input(normalizedMessage)
                    );
                    throw approvalRequired;
                }
                workflowProgress.failed(
                        "REQUEST_ANALYSIS",
                        "Analyzing request",
                        WorkflowProgress.KIND_SYSTEM,
                        elapsedDurationMs(analysisStartedAt),
                        errorMessage(ex),
                        WorkflowProgress.ACTOR_TYPE_SYSTEM,
                        WorkflowProgress.ACTOR_SYSTEM,
                        ChatProgressMetadata.input(normalizedMessage)
                );
                throw ex;
            } finally {
                externalDataCache.clearCachingEnabled();
            }
            executionSteps.addAll(analysisTurn.executionSteps());
            ToolApproval pendingApproval = approvalService.pendingApprovalForWorkflow(workflow.workflowId()).orElse(null);
            if (pendingApproval != null) {
                workflowProgress.completed(
                        "REQUEST_ANALYSIS",
                        "Analyzing request",
                        WorkflowProgress.KIND_SYSTEM,
                        elapsedDurationMs(analysisStartedAt),
                        "Waiting for approval before continuing.",
                        WorkflowProgress.ACTOR_TYPE_SYSTEM,
                        WorkflowProgress.ACTOR_SYSTEM,
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
            workflowProgress.completed(
                    "REQUEST_ANALYSIS",
                    "Analyzing request",
                    WorkflowProgress.KIND_SYSTEM,
                    sumDurationMs(analysisTurn.executionSteps()),
                    "Completed the stock analysis request.",
                    analysisTurn.tokenUsage(),
                    WorkflowProgress.ACTOR_TYPE_SYSTEM,
                    WorkflowProgress.ACTOR_SYSTEM,
                    ChatProgressMetadata.payload(normalizedMessage, analysisTurn.response())
            );

            if (request.saveTurnToMemory()) {
                String memoryMessage = request.memoryMessageOverride() == null || request.memoryMessageOverride().isBlank()
                        ? normalizedMessage
                        : request.memoryMessageOverride().trim();
                saveTurn(conversationId, memoryMessage, analysisTurn, executionSteps, workflow.workflowId());
            } else {
                recordReplayTurnSkip(executionSteps);
            }

            workflow = completeWorkflow(workflow, executionSteps);
            sessionIndexService.recordSessionCompleted(request.userId(), request.sessionId());
            log.info(
                    "chat_workflow_completed mode={} workflowId={} status={} steps={}",
                    request.mode(),
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
                    request.memoryMessageOverride(),
                    request.saveTurnToMemory(),
                    executionSteps,
                    ex.approval(),
                    request.mode()
            );
        } catch (RuntimeException ex) {
            if (workflow != null) {
                log.warn(
                        "chat_workflow_failed mode={} workflowId={} error={}",
                        request.mode(),
                        workflow.workflowId(),
                        ex.getClass().getSimpleName()
                );
            }
            failWorkflow(workflow, ex);
            throw ex;
        } finally {
            if (lease != null) {
                lease.close();
            }
            approvalService.clearRequireApproval();
            WorkflowContextHolder.clear();
        }
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
        workflowProgress.completed(
                approvalStep.id(),
                "Approval required",
                WorkflowProgress.KIND_SYSTEM,
                approvalStep.durationMs(),
                approvalStep.summary(),
                WorkflowProgress.ACTOR_TYPE_SYSTEM,
                WorkflowProgress.ACTOR_SYSTEM,
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
                    approval,
                    waiting.workflowId()
            );
        }
        sessionIndexService.recordSessionCompleted(waiting.userId(), waiting.sessionId());
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
            ToolApproval pendingApproval,
            String workflowId
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
                    pendingApproval,
                    workflowId
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
            List<ChatExecutionStep> executionSteps,
            String workflowId
    ) {
        long saveTurnStartedAt = System.nanoTime();
        String turnPayload = turnPayload(normalizedMessage, analysisTurn.response());
        workflowProgress.running(
                "TURN_SAVE",
                "Saving turn",
                WorkflowProgress.KIND_SYSTEM,
                "Saving the user message and assistant response.",
                WorkflowProgress.ACTOR_TYPE_SYSTEM,
                WorkflowProgress.ACTOR_SYSTEM,
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
                memoryRepository.getLastRetrievedMemories(),
                null,
                workflowId
        );
        ChatExecutionStep saveStep = systemStep(
                "TURN_SAVE",
                "Turn save",
                elapsedDurationMs(saveTurnStartedAt),
                turnSaveSummary(saveSucceeded),
                null
        );
        executionSteps.add(saveStep);
        workflowProgress.completed(
                saveStep.id(),
                "Saving turn",
                WorkflowProgress.KIND_SYSTEM,
                saveStep.durationMs(),
                saveStep.summary(),
                WorkflowProgress.ACTOR_TYPE_SYSTEM,
                WorkflowProgress.ACTOR_SYSTEM,
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
        workflowProgress.completed(
                saveStep.id(),
                "Saving turn",
                WorkflowProgress.KIND_SYSTEM,
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

    private WorkflowMetadata completeWorkflow(WorkflowMetadata workflow, List<ChatExecutionStep> executionSteps) {
        long startedAt = System.nanoTime();
        workflowProgress.running(
                "WORKFLOW_COMPLETE",
                "Completing workflow",
                WorkflowProgress.KIND_SYSTEM,
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
        workflowProgress.completed(
                step.id(),
                "Completing workflow",
                WorkflowProgress.KIND_SYSTEM,
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
        workflowProgress.running(
                "WORKFLOW_FAILURE",
                "Failing workflow",
                WorkflowProgress.KIND_SYSTEM,
                "Marking the workflow failed."
        );
        try {
            WorkflowMetadata failed = workflowService.fail(workflow, failure);
            workflowProgress.failed(
                    "WORKFLOW_FAILURE",
                    "Failing workflow",
                    WorkflowProgress.KIND_SYSTEM,
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

        workflowProgress.workflow(new ChatSessionMetadata(
                List.of(),
                List.of(),
                workflow.workflowId(),
                workflow.status().name(),
                mode,
                replayOrigin.workflowId(),
                replayOrigin.checkpointId(),
                null,
                null,
                workflowStepProjector == null
                        ? List.of()
                        : workflowStepProjector.recoveredSteps(
                                replayOrigin.workflowId(),
                                replayOrigin.checkpointId(),
                                WORKFLOW_EVENT_REPLAY_LIMIT
                        )
        ));
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

    public record ChatRunRequest(
            String userId,
            String sessionId,
            String message,
            String clientRequestId,
            Integer retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled,
            List<String> approvalRequiredTools,
            String mode,
            String replayedFromWorkflowId,
            String replayCheckpointId,
            boolean saveTurnToMemory,
            String memoryMessageOverride
    ) {
        public ChatRunRequest {
            approvalRequiredTools = approvalRequiredTools == null ? List.of() : List.copyOf(approvalRequiredTools);
            mode = mode == null || mode.isBlank() ? "chat" : mode.trim();
        }

        private ReplayOrigin replayOrigin() {
            if ((replayedFromWorkflowId == null || replayedFromWorkflowId.isBlank())
                    && (replayCheckpointId == null || replayCheckpointId.isBlank())) {
                return null;
            }
            return new ReplayOrigin(replayedFromWorkflowId, replayCheckpointId);
        }
    }

    private record ReplayOrigin(String workflowId, String checkpointId) {
    }
}
