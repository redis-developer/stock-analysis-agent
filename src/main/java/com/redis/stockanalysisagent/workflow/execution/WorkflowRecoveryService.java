package com.redis.stockanalysisagent.workflow.execution;

import com.redis.stockanalysisagent.chat.ChatService;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import com.redis.stockanalysisagent.workflow.checkpoint.WorkflowCheckpoint;
import com.redis.stockanalysisagent.workflow.checkpoint.WorkflowCheckpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkflowRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRecoveryService.class);
    private static final int RECOVERY_BATCH_SIZE = 25;
    private static final int REPLAY_RETRIEVED_MEMORIES_LIMIT = 10;

    private final WorkflowService workflowService;
    private final WorkflowQueueService workflowQueueService;
    private final WorkflowCheckpointService checkpointService;
    private final StringRedisTemplate redisTemplate;
    private final ChatService chatService;

    public WorkflowRecoveryService(
            WorkflowService workflowService,
            WorkflowQueueService workflowQueueService,
            WorkflowCheckpointService checkpointService,
            StringRedisTemplate redisTemplate,
            ChatService chatService
    ) {
        this.workflowService = workflowService;
        this.workflowQueueService = workflowQueueService;
        this.checkpointService = checkpointService;
        this.redisTemplate = redisTemplate;
        this.chatService = chatService;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverOnStartup() {
        log.info("workflow_recovery_startup_scan");
        recoverExpiredWorkflows();
    }

    @Scheduled(
            fixedDelayString = "${stock-analysis.workflow.recovery.scan-delay-ms:10000}",
            initialDelayString = "${stock-analysis.workflow.recovery.initial-delay-ms:10000}"
    )
    void recoverExpiredWorkflows() {
        Iterable<WorkflowQueueService.QueuedWorkflow> workflows;
        try {
            workflows = workflowQueueService.claimRecoverableWorkflows(RECOVERY_BATCH_SIZE);
        } catch (RuntimeException ex) {
            log.warn("workflow_recovery_scan_failed error={}", ex.getClass().getSimpleName(), ex);
            return;
        }

        int claimed = 0;
        for (WorkflowQueueService.QueuedWorkflow queuedWorkflow : workflows) {
            String workflowId = queuedWorkflow.workflowId();
            try {
                WorkflowMetadata current = workflowService.readWorkflow(workflowId);
                if (current == null || isTerminalOrWaiting(current)) {
                    workflowQueueService.ack(queuedWorkflow);
                    continue;
                }

                Optional<WorkflowMetadata> workflow = workflowService.tryClaimExpired(workflowId);
                if (workflow.isPresent()) {
                    claimed++;
                    if (recoverClaimedWorkflow(workflow.get())) {
                        workflowQueueService.ack(queuedWorkflow);
                    }
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "workflow_recovery_failed workflowId={} error={}",
                        workflowId,
                        ex.getClass().getSimpleName(),
                        ex
                );
            }
        }
        if (claimed > 0) {
            log.info("workflow_recovery_scan_completed claimed={}", claimed);
        }
    }

    private boolean recoverClaimedWorkflow(WorkflowMetadata workflow) {
        log.info(
                "workflow_recovery_claimed workflowId={} userId={} sessionId={} conversationId={} attempt={}",
                workflow.workflowId(),
                workflow.userId(),
                workflow.sessionId(),
                workflow.conversationId(),
                workflow.attempt()
        );
        try (WorkflowService.Lease ignored = workflowService.renewLeaseUntilClosed(workflow)) {
            Optional<WorkflowCheckpoint> checkpoint = checkpointService.latestCheckpoint(workflow.workflowId());
            if (checkpoint.isEmpty()) {
                workflowService.fail(
                        workflow,
                        new IllegalStateException("Workflow lease expired before a replay checkpoint was created.")
                );
                log.warn("workflow_recovery_no_checkpoint workflowId={}", workflow.workflowId());
                return true;
            }

            try {
                String clientRequestId = recoveryClientRequestId(workflow, checkpoint.get());
                Optional<String> existing = workflowService.recoveredByWorkflowId(workflow.workflowId());
                if (existing.isPresent()) {
                    log.info(
                            "workflow_recovery_idempotent_hit workflowId={} recoveredByWorkflowId={} checkpointId={}",
                            workflow.workflowId(),
                            existing.get(),
                            checkpoint.get().checkpointId()
                    );
                    return true;
                }

                Map<Object, Object> metadata = redisTemplate.opsForHash()
                        .entries(WorkflowService.workflowKey(workflow.workflowId()));
                String originalUserMessage = checkpointService.originalUserMessage(workflow.workflowId())
                        .orElse(checkpoint.get().inputPayload());
                log.info(
                        "workflow_recovery_replay_started workflowId={} checkpointId={} stepId={} originalMessageBytes={}",
                        workflow.workflowId(),
                        checkpoint.get().checkpointId(),
                        checkpoint.get().stepId(),
                        originalUserMessage == null ? 0 : originalUserMessage.length()
                );
                ChatService.ChatTurn turn = chatService.run(new ChatService.ChatRunRequest(
                        workflow.userId(),
                        workflow.sessionId(),
                        checkpointService.replayMessage(workflow.workflowId(), metadata, checkpoint.get()),
                        clientRequestId,
                        REPLAY_RETRIEVED_MEMORIES_LIMIT,
                        true,
                        false,
                        List.of(),
                        "recovery",
                        workflow.workflowId(),
                        checkpoint.get().checkpointId(),
                        true,
                        originalUserMessage
                ));
                workflowService.markRecovered(workflow, turn.workflowId());
                log.info(
                        "workflow_recovery_replay_completed workflowId={} recoveredByWorkflowId={} checkpointId={} responseSteps={}",
                        workflow.workflowId(),
                        turn.workflowId(),
                        checkpoint.get().checkpointId(),
                        turn.executionSteps().size()
                );
                return true;
            } catch (RuntimeException ex) {
                workflowService.fail(workflow, ex);
                log.warn(
                        "workflow_recovery_replay_failed workflowId={} checkpointId={} error={}",
                        workflow.workflowId(),
                        checkpoint.get().checkpointId(),
                        ex.getClass().getSimpleName(),
                        ex
                );
                return true;
            }
        }
    }

    private boolean isTerminalOrWaiting(WorkflowMetadata workflow) {
        return workflow.status() == WorkflowStatus.COMPLETED
                || workflow.status() == WorkflowStatus.FAILED
                || workflow.status() == WorkflowStatus.RECOVERED
                || workflow.status() == WorkflowStatus.WAITING_FOR_APPROVAL;
    }

    private String recoveryClientRequestId(WorkflowMetadata workflow, WorkflowCheckpoint checkpoint) {
        return "recovery:" + workflow.workflowId() + ":" + checkpoint.checkpointId();
    }
}
