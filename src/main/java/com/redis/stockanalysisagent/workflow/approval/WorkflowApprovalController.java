package com.redis.stockanalysisagent.workflow.approval;

import com.redis.stockanalysisagent.cache.ExternalApiUsageService;
import com.redis.stockanalysisagent.cache.ExternalApiUsageSnapshot;
import com.redis.stockanalysisagent.chat.ChatResponse;
import com.redis.stockanalysisagent.chat.ChatService;
import com.redis.stockanalysisagent.session.ChatSessionAccess;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.checkpoint.WorkflowCheckpoint;
import com.redis.stockanalysisagent.workflow.checkpoint.WorkflowCheckpointService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowApprovalController {

    private static final int APPROVAL_RETRIEVED_MEMORIES_LIMIT = 10;

    private final ChatService chatService;
    private final ChatSessionAccess sessionAccess;
    private final WorkflowApprovalService approvalService;
    private final WorkflowCheckpointService checkpointService;
    private final WorkflowService workflowService;
    private final ExternalApiUsageService apiUsageService;

    @Autowired
    public WorkflowApprovalController(
            ChatService chatService,
            ChatSessionAccess sessionAccess,
            WorkflowApprovalService approvalService,
            WorkflowCheckpointService checkpointService,
            WorkflowService workflowService,
            ObjectProvider<ExternalApiUsageService> apiUsageService
    ) {
        this.chatService = chatService;
        this.sessionAccess = sessionAccess;
        this.approvalService = approvalService;
        this.checkpointService = checkpointService;
        this.workflowService = workflowService;
        this.apiUsageService = apiUsageService.getIfAvailable();
    }

    @PostMapping(value = "/{workflowId}/approvals/{approvalId}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse approve(
            @PathVariable String workflowId,
            @PathVariable String approvalId,
            HttpServletRequest httpRequest
    ) {
        return decide(workflowId, approvalId, true, httpRequest);
    }

    @PostMapping(value = "/{workflowId}/approvals/{approvalId}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse reject(
            @PathVariable String workflowId,
            @PathVariable String approvalId,
            HttpServletRequest httpRequest
    ) {
        return decide(workflowId, approvalId, false, httpRequest);
    }

    private ChatResponse decide(String workflowId, String approvalId, boolean approved, HttpServletRequest httpRequest) {
        long startedAt = System.nanoTime();
        HttpSession session = httpRequest.getSession(false);
        String userId = sessionAccess.requireSessionUserId(session);
        ToolApproval pending = approvalService.readRequired(approvalId);
        validateApproval(workflowId, pending, userId);

        ToolApproval approval = approved ? approvalService.approve(approvalId) : approvalService.reject(approvalId);
        if (approved && approval.rejected() || !approved && approval.approved()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval was already decided.");
        }

        WorkflowCheckpoint checkpoint = checkpointService.latestCheckpoint(approval.workflowId()).orElse(null);
        if (checkpoint == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow has no checkpoint to resume.");
        }
        Map<String, String> workflowFields = workflowService.workflowFields(approval.workflowId());
        Map<Object, Object> replayMetadata = new LinkedHashMap<>();
        workflowFields.forEach(replayMetadata::put);
        String replayMessage = checkpointService.replayMessage(approval.workflowId(), replayMetadata, checkpoint)
                + "\n\n"
                + approvalService.replayMessage(approval, approved);
        ChatService.ChatTurn turn = chatService.run(new ChatService.ChatRunRequest(
                approval.userId(),
                approval.sessionId(),
                replayMessage,
                "approval:" + approval.approvalId() + ":" + approval.status(),
                APPROVAL_RETRIEVED_MEMORIES_LIMIT,
                true,
                false,
                sessionAccess.activeApprovalRequiredTools(session),
                "replay",
                approval.workflowId(),
                checkpoint.checkpointId(),
                true,
                checkpointService.originalUserMessage(approval.workflowId()).orElse("")
        ));
        workflowService.markApprovalResolved(approval.workflowId(), turn.workflowId());
        approvalService.recordResume(approval.approvalId(), turn.workflowId());
        return new ChatResponse(
                approval.userId(),
                approval.sessionId(),
                turn.conversationId(),
                turn.response(),
                turn.retrievedMemories(),
                turn.fromSemanticCache(),
                turn.fromSemanticGuardrail(),
                turn.tokenUsage(),
                turn.executionSteps(),
                sessionAccess.activeRetrievedMemoriesLimit(session),
                sessionAccess.activeApiCachingEnabled(session),
                sessionAccess.activeSemanticCachingEnabled(session),
                sessionAccess.activeRateLimitingEnabled(session),
                sessionAccess.activeRequireApprovalEnabled(session),
                sessionAccess.activeApprovalRequiredTools(session),
                providerUsageSnapshot(),
                (System.nanoTime() - startedAt) / 1_000_000,
                turn.tickers(),
                turn.triggeredAgents(),
                turn.workflowId(),
                turn.workflowStatus(),
                turn.pendingApproval()
        );
    }

    private void validateApproval(String workflowId, ToolApproval approval, String userId) {
        if (!approval.workflowId().equals(workflowId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval does not belong to this workflow.");
        }
        if (!approval.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approval belongs to another user.");
        }
        if (approval.resumedWorkflowId() != null && !approval.resumedWorkflowId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval was already resumed.");
        }
    }

    private ExternalApiUsageSnapshot providerUsageSnapshot() {
        if (apiUsageService == null) {
            return ExternalApiUsageSnapshot.empty();
        }
        return apiUsageService.snapshot();
    }
}
