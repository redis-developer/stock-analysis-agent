package com.redis.stockanalysisagent.workflow;

import com.redis.stockanalysisagent.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class WorkflowRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRecoveryService.class);
    private static final int RECOVERY_BATCH_SIZE = 25;
    private static final int REPLAY_RETRIEVED_MEMORIES_LIMIT = 10;

    private final WorkflowService workflowService;
    private final WorkflowCheckpointService checkpointService;
    private final StringRedisTemplate redisTemplate;
    private final ChatService chatService;

    public WorkflowRecoveryService(
            WorkflowService workflowService,
            WorkflowCheckpointService checkpointService,
            StringRedisTemplate redisTemplate,
            ChatService chatService
    ) {
        this.workflowService = workflowService;
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
        Iterable<String> workflowIds;
        try {
            workflowIds = workflowService.expiredRunningWorkflowIds(RECOVERY_BATCH_SIZE);
        } catch (RuntimeException ex) {
            log.warn("workflow_recovery_scan_failed error={}", ex.getClass().getSimpleName(), ex);
            return;
        }

        int claimed = 0;
        for (String workflowId : workflowIds) {
            try {
                Optional<WorkflowMetadata> workflow = workflowService.tryClaimExpired(workflowId);
                if (workflow.isPresent()) {
                    claimed++;
                    recoverClaimedWorkflow(workflow.get());
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

    private void recoverClaimedWorkflow(WorkflowMetadata workflow) {
        log.info(
                "workflow_recovery_claimed workflowId={} userId={} sessionId={} conversationId={} ownerId={} leaseVersion={} attempt={}",
                workflow.workflowId(),
                workflow.userId(),
                workflow.sessionId(),
                workflow.conversationId(),
                workflow.ownerId(),
                workflow.leaseVersion(),
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
                return;
            }

            try {
                String clientRequestId = recoveryClientRequestId(workflow, checkpoint.get());
                Optional<WorkflowService.IdempotentWorkflow> existing =
                        workflowService.completedIdempotentWorkflow(clientRequestId);
                if (existing.isPresent()) {
                    workflowService.markRecovered(workflow, existing.get().workflowId());
                    log.info(
                            "workflow_recovery_idempotent_hit workflowId={} recoveredByWorkflowId={} checkpointId={}",
                            workflow.workflowId(),
                            existing.get().workflowId(),
                            checkpoint.get().checkpointId()
                    );
                    return;
                }

                Optional<WorkflowService.ExecutionLock> lock = workflowService.tryAcquireExecutionLock(clientRequestId);
                if (lock.isEmpty()) {
                    log.info(
                            "workflow_recovery_lock_busy workflowId={} checkpointId={}",
                            workflow.workflowId(),
                            checkpoint.get().checkpointId()
                    );
                    return;
                }

                try (WorkflowService.ExecutionLock executionLock = lock.get()) {
                    existing = workflowService.completedIdempotentWorkflow(clientRequestId);
                    if (existing.isPresent()) {
                        workflowService.markRecovered(workflow, existing.get().workflowId());
                        log.info(
                                "workflow_recovery_idempotent_hit workflowId={} recoveredByWorkflowId={} checkpointId={}",
                                workflow.workflowId(),
                                existing.get().workflowId(),
                                checkpoint.get().checkpointId()
                        );
                        return;
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
                    ChatService.ChatTurn turn = chatService.recover(
                            workflow.userId(),
                            workflow.sessionId(),
                            checkpointService.replayMessage(workflow.workflowId(), metadata, checkpoint.get()),
                            originalUserMessage,
                            clientRequestId,
                            REPLAY_RETRIEVED_MEMORIES_LIMIT,
                            true,
                            false,
                            workflow.workflowId(),
                            checkpoint.get().checkpointId()
                    );
                    workflowService.recordCompletedIdempotentWorkflow(
                            clientRequestId,
                            turn.workflowId(),
                            turn.conversationId(),
                            turn.workflowStatus()
                    );
                    workflowService.markRecovered(workflow, turn.workflowId());
                    log.info(
                            "workflow_recovery_replay_completed workflowId={} recoveredByWorkflowId={} checkpointId={} responseSteps={}",
                            workflow.workflowId(),
                            turn.workflowId(),
                            checkpoint.get().checkpointId(),
                            turn.executionSteps().size()
                    );
                }
            } catch (RuntimeException ex) {
                workflowService.fail(workflow, ex);
                log.warn(
                        "workflow_recovery_replay_failed workflowId={} checkpointId={} error={}",
                        workflow.workflowId(),
                        checkpoint.get().checkpointId(),
                        ex.getClass().getSimpleName(),
                        ex
                );
                throw ex;
            }
        }
    }

    private String recoveryClientRequestId(WorkflowMetadata workflow, WorkflowCheckpoint checkpoint) {
        return "recovery:" + workflow.workflowId() + ":" + checkpoint.checkpointId();
    }
}
