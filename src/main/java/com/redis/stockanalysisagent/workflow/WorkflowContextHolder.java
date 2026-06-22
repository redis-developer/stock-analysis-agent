package com.redis.stockanalysisagent.workflow;

import java.util.Optional;

public final class WorkflowContextHolder {

    private static final ThreadLocal<String> ACTIVE_WORKFLOW_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> APPROVAL_WORKFLOW_ID = new ThreadLocal<>();

    private WorkflowContextHolder() {
    }

    public static void setWorkflowId(String workflowId) {
        setWorkflowId(workflowId, null);
    }

    public static void setWorkflowId(String workflowId, String approvalWorkflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            clear();
            return;
        }
        ACTIVE_WORKFLOW_ID.set(workflowId);
        if (approvalWorkflowId == null || approvalWorkflowId.isBlank()) {
            APPROVAL_WORKFLOW_ID.remove();
        } else {
            APPROVAL_WORKFLOW_ID.set(approvalWorkflowId);
        }
    }

    public static Optional<String> workflowId() {
        return Optional.ofNullable(ACTIVE_WORKFLOW_ID.get());
    }

    public static Optional<String> approvalWorkflowId() {
        return Optional.ofNullable(APPROVAL_WORKFLOW_ID.get());
    }

    public static void clear() {
        ACTIVE_WORKFLOW_ID.remove();
        APPROVAL_WORKFLOW_ID.remove();
    }
}
