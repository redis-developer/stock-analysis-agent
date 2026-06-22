package com.redis.stockanalysisagent.workflow;

public enum WorkflowStatus {
    RUNNING,
    RECOVERING,
    WAITING_FOR_APPROVAL,
    RECOVERED,
    COMPLETED,
    FAILED
}
