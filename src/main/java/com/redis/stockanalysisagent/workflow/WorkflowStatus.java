package com.redis.stockanalysisagent.workflow;

public enum WorkflowStatus {
    RUNNING,
    RECOVERING,
    RECOVERED,
    COMPLETED,
    FAILED
}
