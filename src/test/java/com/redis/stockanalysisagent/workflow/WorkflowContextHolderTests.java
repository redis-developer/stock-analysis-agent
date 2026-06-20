package com.redis.stockanalysisagent.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContextHolderTests {

    @Test
    void storesAndClearsWorkflowIdForCurrentThread() {
        WorkflowContextHolder.setWorkflowId("workflow-1");

        assertThat(WorkflowContextHolder.workflowId()).contains("workflow-1");

        WorkflowContextHolder.clear();

        assertThat(WorkflowContextHolder.workflowId()).isEmpty();
    }

    @Test
    void blankWorkflowIdClearsExistingValue() {
        WorkflowContextHolder.setWorkflowId("workflow-1");

        WorkflowContextHolder.setWorkflowId(" ");

        assertThat(WorkflowContextHolder.workflowId()).isEmpty();
    }
}
