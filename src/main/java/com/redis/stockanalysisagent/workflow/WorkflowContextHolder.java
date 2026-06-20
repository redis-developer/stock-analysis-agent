package com.redis.stockanalysisagent.workflow;

import java.util.Optional;

public final class WorkflowContextHolder {

    private static final ThreadLocal<String> ACTIVE_WORKFLOW_ID = new ThreadLocal<>();

    private WorkflowContextHolder() {
    }

    public static void setWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            clear();
            return;
        }
        ACTIVE_WORKFLOW_ID.set(workflowId);
    }

    public static Optional<String> workflowId() {
        return Optional.ofNullable(ACTIVE_WORKFLOW_ID.get());
    }

    public static void clear() {
        ACTIVE_WORKFLOW_ID.remove();
    }
}
