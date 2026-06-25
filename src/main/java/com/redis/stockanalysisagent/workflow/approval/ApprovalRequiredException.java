package com.redis.stockanalysisagent.workflow.approval;

public class ApprovalRequiredException extends RuntimeException {

    private final ToolApproval approval;

    public ApprovalRequiredException(ToolApproval approval) {
        super("Approval required before running " + approval.toolName() + ".");
        this.approval = approval;
    }

    public ToolApproval approval() {
        return approval;
    }
}
