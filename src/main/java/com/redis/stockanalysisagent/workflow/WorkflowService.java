package com.redis.stockanalysisagent.workflow;

import com.redis.stockanalysisagent.workflow.approval.ToolApproval;
import com.redis.stockanalysisagent.workflow.events.WorkflowEventService;
import com.redis.stockanalysisagent.workflow.execution.WorkflowLeaseService;
import com.redis.stockanalysisagent.workflow.execution.WorkflowQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    static final String KEY_PREFIX = "stock-analysis:workflows:";
    static final String SESSION_KEY_PREFIX = "stock-analysis:sessions:";
    static final String SESSION_WORKFLOWS_SUFFIX = ":workflows";
    static final String USER_KEY_PREFIX = "stock-analysis:users:";
    static final String USER_CONVERSATIONS_SUFFIX = ":conversations";
    static final String USER_WORKFLOWS_SUFFIX = ":workflows";
    static final String CONVERSATION_KEY_PREFIX = "stock-analysis:conversations:";
    static final String CONVERSATION_WORKFLOWS_SUFFIX = ":workflows";
    static final Duration WORKFLOW_TTL = Duration.ofHours(24);
    public static final String REPLAYED_FROM_WORKFLOW_ID = "replayedFromWorkflowId";
    public static final String REPLAY_CHECKPOINT_ID = "replayCheckpointId";
    public static final String RECOVERED_BY_WORKFLOW_ID = "recoveredByWorkflowId";
    private final StringRedisTemplate redisTemplate;
    private final WorkflowLeaseService workflowLeaseService;
    private final Clock clock;
    private final Supplier<String> workflowIdSupplier;

    @Autowired
    public WorkflowService(
            StringRedisTemplate redisTemplate,
            WorkflowLeaseService workflowLeaseService
    ) {
        this(
                redisTemplate,
                workflowLeaseService,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString()
        );
    }

    WorkflowService(StringRedisTemplate redisTemplate, Clock clock, Supplier<String> workflowIdSupplier) {
        this(redisTemplate, clock, workflowIdSupplier, () -> UUID.randomUUID().toString());
    }

    WorkflowService(
            StringRedisTemplate redisTemplate,
            Clock clock,
            Supplier<String> workflowIdSupplier,
            Supplier<String> workerIdSupplier
    ) {
        this(
                redisTemplate,
                new WorkflowLeaseService(
                        redisTemplate,
                        workerIdSupplier,
                        WorkflowLeaseService.DEFAULT_WORKFLOW_LEASE,
                        WorkflowLeaseService.DEFAULT_WORKFLOW_LEASE_RENEWAL_INTERVAL
                ),
                clock,
                workflowIdSupplier
        );
    }

    WorkflowService(
            StringRedisTemplate redisTemplate,
            WorkflowLeaseService workflowLeaseService,
            Clock clock,
            Supplier<String> workflowIdSupplier
    ) {
        this.redisTemplate = redisTemplate;
        this.workflowLeaseService = workflowLeaseService;
        this.clock = clock;
        this.workflowIdSupplier = workflowIdSupplier;
    }

    public WorkflowMetadata start(
            String userId,
            String sessionId,
            String conversationId,
            String clientRequestId
    ) {
        return start(userId, sessionId, conversationId, clientRequestId, null, null);
    }

    public WorkflowMetadata start(
            String userId,
            String sessionId,
            String conversationId,
            String clientRequestId,
            String replayedFromWorkflowId,
            String replayCheckpointId
    ) {
        Instant now = Instant.now(clock);
        SessionWorkflowPosition position = sessionWorkflowPosition(sessionId);
        WorkflowMetadata metadata = new WorkflowMetadata(
                workflowIdSupplier.get(),
                blankToNull(clientRequestId),
                userId,
                sessionId,
                conversationId,
                WorkflowStatus.RUNNING,
                position.previousWorkflowId(),
                position.turnIndex(),
                1,
                now,
                now,
                null,
                null
        );
        Map<String, String> fields = allFields(metadata);
        fields.putAll(replayFields(replayedFromWorkflowId, replayCheckpointId));
        writePipelined(metadata.workflowId(), fields, metadata);
        log.info(
                "workflow_started workflowId={} status={} workerId={} attempt={} userId={} sessionId={} conversationId={} replayedFromWorkflowId={} replayCheckpointId={}",
                metadata.workflowId(),
                metadata.status(),
                workflowLeaseService.workerId(),
                metadata.attempt(),
                metadata.userId(),
                metadata.sessionId(),
                metadata.conversationId(),
                blankToNull(replayedFromWorkflowId),
                blankToNull(replayCheckpointId)
        );
        return metadata;
    }

    public WorkflowMetadata complete(WorkflowMetadata workflow) {
        return terminal(workflow, WorkflowStatus.COMPLETED, null);
    }

    public WorkflowMetadata waitingForApproval(WorkflowMetadata workflow, ToolApproval approval) {
        Instant now = Instant.now(clock);
        WorkflowMetadata metadata = new WorkflowMetadata(
                workflow.workflowId(),
                workflow.clientRequestId(),
                workflow.userId(),
                workflow.sessionId(),
                workflow.conversationId(),
                WorkflowStatus.WAITING_FOR_APPROVAL,
                workflow.previousWorkflowId(),
                workflow.turnIndex(),
                workflow.attempt(),
                workflow.createdAt(),
                now,
                null,
                null
        );
        Map<String, String> fields = allFields(metadata);
        put(fields, "approvalId", approval.approvalId());
        put(fields, "approvalToolName", approval.toolName());
        put(fields, "approvalStatus", approval.status());
        put(fields, "approvalAgentType", approval.agentType());
        put(fields, "approvalTicker", approval.ticker());
        writeWaitingForApproval(metadata, fields);
        return metadata;
    }

    public WorkflowMetadata fail(WorkflowMetadata workflow, RuntimeException failure) {
        return terminal(workflow, WorkflowStatus.FAILED, errorMessage(failure));
    }

    @EventListener(ApplicationReadyEvent.class)
    void rebuildUserWorkflowIndexes() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return;
            }

            for (String key : keys) {
                if (key.endsWith(WorkflowEventService.EVENTS_SUFFIX)) {
                    continue;
                }
                if (redisTemplate.type(key) != DataType.HASH) {
                    continue;
                }
                String workflowId = key.substring(KEY_PREFIX.length());
                Object userId = redisTemplate.opsForHash().get(key, "userId");
                Object conversationId = redisTemplate.opsForHash().get(key, "conversationId");
                redisTemplate.executePipelined(new SessionCallback<Object>() {
                    @Override
                    public Object execute(RedisOperations operations) throws DataAccessException {
                        if (userId instanceof String user && !user.isBlank()) {
                            indexUserWorkflow(operations, user, workflowId);
                            if (conversationId instanceof String conversation && !conversation.isBlank()) {
                                indexUserConversation(operations, user, conversation);
                            }
                        }
                        if (conversationId instanceof String conversation && !conversation.isBlank()) {
                            indexConversationWorkflow(operations, conversation, workflowId);
                        }
                        return null;
                    }
                });
            }
        } catch (DataAccessException ex) {
            log.warn("Skipped workflow user index rebuild: {}", ex.getMessage());
        }
    }

    public static String workflowKey(String workflowId) {
        return KEY_PREFIX + workflowId;
    }

    public static String sessionWorkflowsKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId + SESSION_WORKFLOWS_SUFFIX;
    }

    public static String userWorkflowsKey(String userId) {
        return USER_KEY_PREFIX + userId + USER_WORKFLOWS_SUFFIX;
    }

    public static String userConversationsKey(String userId) {
        return USER_KEY_PREFIX + userId + USER_CONVERSATIONS_SUFFIX;
    }

    public static String conversationWorkflowsKey(String conversationId) {
        return CONVERSATION_KEY_PREFIX + conversationId + CONVERSATION_WORKFLOWS_SUFFIX;
    }

    public static Duration workflowTtl() {
        return WORKFLOW_TTL;
    }

    public Optional<WorkflowMetadata> tryClaimExpired(String workflowId) {
        WorkflowMetadata current = readWorkflow(workflowId);
        if (current == null || !recoverableStatus(current.status())) {
            return Optional.empty();
        }
        return claimForRecovery(current, false);
    }

    public Optional<WorkflowMetadata> tryClaimForReplay(String workflowId) {
        WorkflowMetadata current = readWorkflow(workflowId);
        if (current == null || !replayableStatus(current.status())) {
            return Optional.empty();
        }
        return claimForRecovery(current, true);
    }

    public Optional<String> recoveredByWorkflowId(String workflowId) {
        Map<String, String> fields = workflowFields(workflowId);
        String recoveredByWorkflowId = blankToNull(fields.get(RECOVERED_BY_WORKFLOW_ID));
        if (!"RECOVERED".equals(fields.get("status")) || recoveredByWorkflowId == null) {
            return Optional.empty();
        }
        return Optional.of(recoveredByWorkflowId);
    }

    private Optional<WorkflowMetadata> claimForRecovery(WorkflowMetadata current, boolean allowFailed) {
        String workflowId = current.workflowId();
        if (!workflowLeaseService.acquire(workflowId)) {
            log.debug("workflow_claim_skipped workflowId={} workerId={}", workflowId, workflowLeaseService.workerId());
            return Optional.empty();
        }

        current = readWorkflow(workflowId);
        if (current == null || !(recoverableStatus(current.status())
                || allowFailed && current.status() == WorkflowStatus.FAILED)) {
            workflowLeaseService.release(workflowId);
            return Optional.empty();
        }

        Instant now = Instant.now(clock);
        WorkflowMetadata claimed = new WorkflowMetadata(
                current.workflowId(),
                current.clientRequestId(),
                current.userId(),
                current.sessionId(),
                current.conversationId(),
                WorkflowStatus.RECOVERING,
                current.previousWorkflowId(),
                current.turnIndex(),
                current.attempt() == null ? 1 : current.attempt() + 1,
                current.createdAt(),
                now,
                null,
                null
        );
        writePipelined(claimed.workflowId(), allFields(claimed));
        log.info(
                "workflow_claimed workflowId={} workerId={} attempt={}",
                workflowId,
                workflowLeaseService.workerId(),
                claimed.attempt()
        );
        return Optional.of(claimed);
    }

    public Lease renewLeaseUntilClosed(WorkflowMetadata workflow) {
        if (workflow == null) {
            return Lease.noop();
        }
        return new Lease(workflowLeaseService.renewUntilClosed(workflow.workflowId(), workflow.status()));
    }

    public boolean renewLease(WorkflowMetadata workflow) {
        return workflow != null && workflowLeaseService.renew(workflow.workflowId());
    }

    public WorkflowMetadata markRecovered(WorkflowMetadata workflow, String recoveredByWorkflowId) {
        return terminal(workflow, WorkflowStatus.RECOVERED, null, recoveredByWorkflowId);
    }

    public WorkflowMetadata markApprovalResolved(String workflowId, String resumedWorkflowId) {
        WorkflowMetadata workflow = readWorkflow(workflowId);
        if (workflow == null || workflow.status() != WorkflowStatus.WAITING_FOR_APPROVAL) {
            return workflow;
        }

        Instant now = Instant.now(clock);
        WorkflowMetadata metadata = new WorkflowMetadata(
                workflow.workflowId(),
                workflow.clientRequestId(),
                workflow.userId(),
                workflow.sessionId(),
                workflow.conversationId(),
                WorkflowStatus.RECOVERED,
                workflow.previousWorkflowId(),
                workflow.turnIndex(),
                workflow.attempt(),
                workflow.createdAt(),
                now,
                now,
                null
        );
        Map<String, String> fields = allFields(metadata);
        put(fields, RECOVERED_BY_WORKFLOW_ID, resumedWorkflowId);
        writePipelined(metadata.workflowId(), fields);
        log.info(
                "workflow_approval_resolved workflowId={} resumedWorkflowId={}",
                metadata.workflowId(),
                blankToNull(resumedWorkflowId)
        );
        return metadata;
    }

    public WorkflowMetadata readWorkflow(String workflowId) {
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(workflowKey(workflowId));
        if (fields.isEmpty()) {
            return null;
        }
        return metadata(fields);
    }

    public Map<String, String> workflowFields(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Map.of();
        }

        Map<Object, Object> fields = redisTemplate.opsForHash().entries(workflowKey(workflowId));
        if (fields.isEmpty()) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key != null && value != null) {
                values.put(key.toString(), value.toString());
            }
        });
        return values;
    }

    public List<String> sessionWorkflowIds(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<String> workflowIds = redisTemplate.opsForList().range(sessionWorkflowsKey(sessionId), 0, -1);
        return workflowIds == null ? List.of() : List.copyOf(workflowIds);
    }

    private WorkflowMetadata terminal(WorkflowMetadata workflow, WorkflowStatus status, String failureReason) {
        return terminal(workflow, status, failureReason, "");
    }

    private WorkflowMetadata terminal(
            WorkflowMetadata workflow,
            WorkflowStatus status,
            String failureReason,
            String recoveredByWorkflowId
    ) {
        Instant now = Instant.now(clock);
        WorkflowMetadata metadata = new WorkflowMetadata(
                workflow.workflowId(),
                workflow.clientRequestId(),
                workflow.userId(),
                workflow.sessionId(),
                workflow.conversationId(),
                status,
                workflow.previousWorkflowId(),
                workflow.turnIndex(),
                workflow.attempt(),
                workflow.createdAt(),
                now,
                now,
                failureReason
        );
        writeTerminal(metadata, recoveredByWorkflowId);
        return metadata;
    }

    private void writeTerminal(WorkflowMetadata metadata, String recoveredByWorkflowId) {
        if (!workflowLeaseService.isLeaseHolder(metadata.workflowId())) {
            log.warn(
                    "workflow_terminal_rejected workflowId={} status={} workerId={}",
                    metadata.workflowId(),
                    metadata.status(),
                    workflowLeaseService.workerId()
            );
            throw new WorkflowOwnershipException(metadata.workflowId());
        }
        Map<String, String> fields = allFields(metadata);
        put(fields, RECOVERED_BY_WORKFLOW_ID, blankToNull(recoveredByWorkflowId));
        writePipelined(metadata.workflowId(), fields);
        log.info(
                "workflow_terminal workflowId={} status={} workerId={} recoveredByWorkflowId={} failureReasonPresent={}",
                metadata.workflowId(),
                metadata.status(),
                workflowLeaseService.workerId(),
                blankToNull(recoveredByWorkflowId),
                metadata.failureReason() != null && !metadata.failureReason().isBlank()
        );
    }

    private void writeWaitingForApproval(WorkflowMetadata metadata, Map<String, String> fields) {
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                String key = workflowKey(metadata.workflowId());
                operations.opsForHash().putAll(key, fields);
                operations.expire(key, WORKFLOW_TTL);
                return null;
            }
        });
        log.info(
                "workflow_waiting_for_approval workflowId={} approvalId={} workerId={}",
                metadata.workflowId(),
                fields.getOrDefault("approvalId", ""),
                workflowLeaseService.workerId()
        );
    }

    private Map<String, String> allFields(WorkflowMetadata metadata) {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "workflowId", metadata.workflowId());
        put(fields, "clientRequestId", metadata.clientRequestId());
        put(fields, "userId", metadata.userId());
        put(fields, "sessionId", metadata.sessionId());
        put(fields, "conversationId", metadata.conversationId());
        put(fields, "status", metadata.status().name());
        put(fields, "previousWorkflowId", metadata.previousWorkflowId());
        put(fields, "turnIndex", metadata.turnIndex());
        put(fields, "attempt", metadata.attempt());
        put(fields, "createdAt", metadata.createdAt());
        put(fields, "updatedAt", metadata.updatedAt());
        put(fields, "finishedAt", metadata.finishedAt());
        put(fields, "failureReason", metadata.failureReason());
        return fields;
    }

    private Map<String, String> replayFields(String replayedFromWorkflowId, String replayCheckpointId) {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, REPLAYED_FROM_WORKFLOW_ID, blankToNull(replayedFromWorkflowId));
        put(fields, REPLAY_CHECKPOINT_ID, blankToNull(replayCheckpointId));
        return fields;
    }

    private void writePipelined(String workflowId, Map<String, String> fields) {
        writePipelined(workflowId, fields, null);
    }

    private void writePipelined(String workflowId, Map<String, String> fields, WorkflowMetadata startedWorkflow) {
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                String key = workflowKey(workflowId);
                operations.opsForHash().putAll(key, fields);
                operations.expire(key, WORKFLOW_TTL);
                if (startedWorkflow != null) {
                    workflowLeaseService.startLease(operations, startedWorkflow.workflowId());
                    operations.opsForStream().add(
                            WorkflowQueueService.RECOVERY_STREAM_KEY,
                            WorkflowQueueService.recoveryFields(startedWorkflow)
                    );
                    operations.expire(WorkflowQueueService.RECOVERY_STREAM_KEY, WORKFLOW_TTL);
                }
                if (startedWorkflow != null && startedWorkflow.sessionId() != null && !startedWorkflow.sessionId().isBlank()) {
                    String sessionKey = sessionWorkflowsKey(startedWorkflow.sessionId());
                    operations.opsForList().rightPush(sessionKey, startedWorkflow.workflowId());
                    operations.expire(sessionKey, WORKFLOW_TTL);
                }
                if (startedWorkflow != null && startedWorkflow.userId() != null && !startedWorkflow.userId().isBlank()) {
                    indexUserWorkflow(operations, startedWorkflow.userId(), startedWorkflow.workflowId());
                    if (startedWorkflow.conversationId() != null && !startedWorkflow.conversationId().isBlank()) {
                        indexUserConversation(operations, startedWorkflow.userId(), startedWorkflow.conversationId());
                    }
                }
                if (startedWorkflow != null && startedWorkflow.conversationId() != null && !startedWorkflow.conversationId().isBlank()) {
                    indexConversationWorkflow(
                            operations,
                            startedWorkflow.conversationId(),
                            startedWorkflow.workflowId()
                    );
                }
                return null;
            }
        });
    }

    private void indexUserWorkflow(RedisOperations operations, String userId, String workflowId) {
        String userKey = userWorkflowsKey(userId);
        operations.opsForList().remove(userKey, 0, workflowId);
        operations.opsForList().leftPush(userKey, workflowId);
        operations.expire(userKey, WORKFLOW_TTL);
    }

    private void indexUserConversation(RedisOperations operations, String userId, String conversationId) {
        String userKey = userConversationsKey(userId);
        operations.opsForList().remove(userKey, 0, conversationId);
        operations.opsForList().leftPush(userKey, conversationId);
        operations.expire(userKey, WORKFLOW_TTL);
    }

    private void indexConversationWorkflow(RedisOperations operations, String conversationId, String workflowId) {
        String conversationKey = conversationWorkflowsKey(conversationId);
        operations.opsForList().remove(conversationKey, 0, workflowId);
        operations.opsForList().leftPush(conversationKey, workflowId);
        operations.expire(conversationKey, WORKFLOW_TTL);
    }

    private SessionWorkflowPosition sessionWorkflowPosition(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new SessionWorkflowPosition(null, 1);
        }

        String key = sessionWorkflowsKey(sessionId);
        @SuppressWarnings("unchecked")
        List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForList().size(key);
                operations.opsForList().index(key, -1);
                return null;
            }
        });

        long currentSize = results != null && !results.isEmpty() && results.getFirst() instanceof Number number
                ? number.longValue()
                : 0;
        String previousWorkflowId = results != null && results.size() > 1 && results.get(1) instanceof String value
                ? value
                : null;
        return new SessionWorkflowPosition(previousWorkflowId, Math.toIntExact(currentSize + 1));
    }

    private WorkflowMetadata metadata(Map<Object, Object> fields) {
        return new WorkflowMetadata(
                value(fields, "workflowId"),
                blankToNull(value(fields, "clientRequestId")),
                value(fields, "userId"),
                value(fields, "sessionId"),
                value(fields, "conversationId"),
                status(value(fields, "status")),
                blankToNull(value(fields, "previousWorkflowId")),
                integer(value(fields, "turnIndex"), 1),
                integer(value(fields, "attempt"), 1),
                instant(value(fields, "createdAt")),
                instant(value(fields, "updatedAt")),
                instant(value(fields, "finishedAt")),
                blankToNull(value(fields, "failureReason"))
        );
    }

    private String value(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        return value == null ? "" : value.toString();
    }

    private WorkflowStatus status(String value) {
        if (value == null || value.isBlank()) {
            return WorkflowStatus.RUNNING;
        }
        return WorkflowStatus.valueOf(value);
    }

    private boolean recoverableStatus(WorkflowStatus status) {
        return status == WorkflowStatus.RUNNING || status == WorkflowStatus.RECOVERING;
    }

    private boolean replayableStatus(WorkflowStatus status) {
        return status == WorkflowStatus.RUNNING
                || status == WorkflowStatus.RECOVERING
                || status == WorkflowStatus.FAILED;
    }

    private Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private Integer integer(String value, Integer fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private void put(Map<String, String> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value.toString());
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record SessionWorkflowPosition(
            String previousWorkflowId,
            int turnIndex
    ) {
    }

    public static final class Lease implements AutoCloseable {

        private final WorkflowLeaseService.Lease delegate;

        private Lease(WorkflowLeaseService.Lease delegate) {
            this.delegate = delegate;
        }

        public static Lease noop() {
            return new Lease(WorkflowLeaseService.Lease.noop());
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    public static class WorkflowOwnershipException extends RuntimeException {

        WorkflowOwnershipException(String workflowId) {
            super("Workflow %s is no longer owned by this worker.".formatted(workflowId));
        }
    }
}
