package com.redis.stockanalysisagent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    static final String KEY_PREFIX = "stock-analysis:workflows:";
    static final String SESSION_KEY_PREFIX = "stock-analysis:sessions:";
    static final String SESSION_WORKFLOWS_SUFFIX = ":workflows";
    static final String RUNNING_WORKFLOWS_KEY = "stock-analysis:workflows:running";
    static final String EXECUTION_LOCK_KEY_PREFIX = "stock-analysis:workflow-execution-locks:";
    static final String IDEMPOTENCY_KEY_PREFIX = "stock-analysis:workflow-idempotency:";
    static final String USER_KEY_PREFIX = "stock-analysis:users:";
    static final String USER_CONVERSATIONS_SUFFIX = ":conversations";
    static final String USER_WORKFLOWS_SUFFIX = ":workflows";
    static final String CONVERSATION_KEY_PREFIX = "stock-analysis:conversations:";
    static final String CONVERSATION_WORKFLOWS_SUFFIX = ":workflows";
    static final Duration WORKFLOW_TTL = Duration.ofHours(24);
    static final Duration DEFAULT_WORKFLOW_LEASE = Duration.ofSeconds(15);
    static final Duration DEFAULT_WORKFLOW_LEASE_RENEWAL_INTERVAL = Duration.ofSeconds(5);
    static final Duration WORKFLOW_EXECUTION_LOCK_TTL = Duration.ofMinutes(10);
    public static final String REPLAYED_FROM_WORKFLOW_ID = "replayedFromWorkflowId";
    public static final String REPLAY_CHECKPOINT_ID = "replayCheckpointId";
    public static final String RECOVERED_BY_WORKFLOW_ID = "recoveredByWorkflowId";
    private static final RedisScript<Long> CLAIM_EXPIRED_SCRIPT = new DefaultRedisScript<>("""
            local workflow_key = KEYS[1]
            local running_key = KEYS[2]
            local status = redis.call('HGET', workflow_key, 'status')
            if status ~= 'RUNNING' and status ~= 'RECOVERING' then
              return 0
            end
            local lease_until = tonumber(redis.call('HGET', workflow_key, 'leaseUntilEpochMs') or '0')
            if lease_until > tonumber(ARGV[1]) then
              return 0
            end
            redis.call('HINCRBY', workflow_key, 'leaseVersion', 1)
            redis.call('HINCRBY', workflow_key, 'attempt', 1)
            redis.call('HSET', workflow_key,
              'status', 'RECOVERING',
              'ownerId', ARGV[2],
              'leaseUntil', ARGV[3],
              'leaseUntilEpochMs', ARGV[4],
              'updatedAt', ARGV[5])
            redis.call('ZADD', running_key, ARGV[4], ARGV[6])
            redis.call('EXPIRE', workflow_key, ARGV[7])
            redis.call('EXPIRE', running_key, ARGV[7])
            return 1
            """, Long.class);
    private static final RedisScript<Long> RENEW_LEASE_SCRIPT = new DefaultRedisScript<>("""
            local workflow_key = KEYS[1]
            local running_key = KEYS[2]
            local status = redis.call('HGET', workflow_key, 'status')
            if status ~= 'RUNNING' and status ~= 'RECOVERING' then
              return 0
            end
            if redis.call('HGET', workflow_key, 'ownerId') ~= ARGV[1] then
              return -1
            end
            if redis.call('HGET', workflow_key, 'leaseVersion') ~= ARGV[2] then
              return -2
            end
            redis.call('HSET', workflow_key,
              'leaseUntil', ARGV[3],
              'leaseUntilEpochMs', ARGV[4],
              'updatedAt', ARGV[5])
            redis.call('ZADD', running_key, ARGV[4], ARGV[6])
            redis.call('EXPIRE', workflow_key, ARGV[7])
            redis.call('EXPIRE', running_key, ARGV[7])
            return 1
            """, Long.class);
    private static final RedisScript<Long> TERMINAL_SCRIPT = new DefaultRedisScript<>("""
            local workflow_key = KEYS[1]
            local running_key = KEYS[2]
            local status = redis.call('HGET', workflow_key, 'status')
            if status ~= 'RUNNING' and status ~= 'RECOVERING' then
              return 0
            end
            if redis.call('HGET', workflow_key, 'ownerId') ~= ARGV[1] then
              return -1
            end
            if redis.call('HGET', workflow_key, 'leaseVersion') ~= ARGV[2] then
              return -2
            end
            redis.call('HSET', workflow_key,
              'status', ARGV[3],
              'updatedAt', ARGV[4],
              'finishedAt', ARGV[5])
            if ARGV[6] == '' then
              redis.call('HDEL', workflow_key, 'failureReason')
            else
              redis.call('HSET', workflow_key, 'failureReason', ARGV[6])
            end
            if ARGV[7] ~= '' then
              redis.call('HSET', workflow_key, 'recoveredByWorkflowId', ARGV[7])
            end
            redis.call('ZREM', running_key, ARGV[8])
            redis.call('EXPIRE', workflow_key, ARGV[9])
            redis.call('EXPIRE', running_key, ARGV[9])
            return 1
            """, Long.class);
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final Supplier<String> workflowIdSupplier;
    private final String ownerId;
    private final Duration workflowLease;
    private final Duration workflowLeaseRenewalInterval;
    private final ScheduledExecutorService leaseRenewalExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "workflow-lease-renewal");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public WorkflowService(
            StringRedisTemplate redisTemplate,
            @Value("${stock-analysis.workflow.lease:15s}") Duration workflowLease,
            @Value("${stock-analysis.workflow.lease-renewal-interval:5s}") Duration workflowLeaseRenewalInterval
    ) {
        this(
                redisTemplate,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                () -> UUID.randomUUID().toString(),
                workflowLease,
                workflowLeaseRenewalInterval
        );
    }

    WorkflowService(StringRedisTemplate redisTemplate, Clock clock, Supplier<String> workflowIdSupplier) {
        this(redisTemplate, clock, workflowIdSupplier, () -> UUID.randomUUID().toString());
    }

    WorkflowService(
            StringRedisTemplate redisTemplate,
            Clock clock,
            Supplier<String> workflowIdSupplier,
            Supplier<String> ownerIdSupplier
    ) {
        this(
                redisTemplate,
                clock,
                workflowIdSupplier,
                ownerIdSupplier,
                DEFAULT_WORKFLOW_LEASE,
                DEFAULT_WORKFLOW_LEASE_RENEWAL_INTERVAL
        );
    }

    WorkflowService(
            StringRedisTemplate redisTemplate,
            Clock clock,
            Supplier<String> workflowIdSupplier,
            Supplier<String> ownerIdSupplier,
            Duration workflowLease,
            Duration workflowLeaseRenewalInterval
    ) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.workflowIdSupplier = workflowIdSupplier;
        this.ownerId = ownerIdSupplier.get();
        this.workflowLease = workflowLease;
        this.workflowLeaseRenewalInterval = workflowLeaseRenewalInterval;
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
        Instant leaseUntil = leaseUntil(now);
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
                ownerId,
                leaseUntil,
                1L,
                1,
                now,
                now,
                null,
                null
        );
        writeStarted(metadata, replayFields(replayedFromWorkflowId, replayCheckpointId));
        log.info(
                "workflow_started workflowId={} status={} ownerId={} leaseUntil={} leaseVersion={} attempt={} userId={} sessionId={} conversationId={} replayedFromWorkflowId={} replayCheckpointId={}",
                metadata.workflowId(),
                metadata.status(),
                metadata.ownerId(),
                metadata.leaseUntil(),
                metadata.leaseVersion(),
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
                workflow.ownerId(),
                workflow.leaseUntil(),
                workflow.leaseVersion(),
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

    void writeStarted(WorkflowMetadata metadata) {
        writeStarted(metadata, Map.of());
    }

    void writeStarted(WorkflowMetadata metadata, Map<String, String> extraFields) {
        Map<String, String> fields = allFields(metadata);
        fields.putAll(extraFields);
        writePipelined(metadata.workflowId(), fields, metadata);
    }

    void writeTerminal(WorkflowMetadata metadata) {
        writeTerminal(metadata, "");
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
                if (userId instanceof String user && !user.isBlank()) {
                    indexUserWorkflow(user, workflowId);
                    if (conversationId instanceof String conversation && !conversation.isBlank()) {
                        indexUserConversation(user, conversation);
                    }
                }
                if (conversationId instanceof String conversation && !conversation.isBlank()) {
                    indexConversationWorkflow(conversation, workflowId);
                }
            }
        } catch (DataAccessException ex) {
            log.warn("Skipped workflow user index rebuild: {}", ex.getMessage());
        }
    }

    static String workflowKey(String workflowId) {
        return KEY_PREFIX + workflowId;
    }

    static String sessionWorkflowsKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId + SESSION_WORKFLOWS_SUFFIX;
    }

    static String userWorkflowsKey(String userId) {
        return USER_KEY_PREFIX + userId + USER_WORKFLOWS_SUFFIX;
    }

    static String userConversationsKey(String userId) {
        return USER_KEY_PREFIX + userId + USER_CONVERSATIONS_SUFFIX;
    }

    static String conversationWorkflowsKey(String conversationId) {
        return CONVERSATION_KEY_PREFIX + conversationId + CONVERSATION_WORKFLOWS_SUFFIX;
    }

    static String runningWorkflowsKey() {
        return RUNNING_WORKFLOWS_KEY;
    }

    static String executionLockKey(String idempotencyKey) {
        return EXECUTION_LOCK_KEY_PREFIX + idempotencyKey;
    }

    static String idempotencyKey(String idempotencyKey) {
        return IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
    }

    static Duration workflowTtl() {
        return WORKFLOW_TTL;
    }

    public List<String> expiredRunningWorkflowIds(int limit) {
        Instant now = Instant.now(clock);
        Set<String> workflowIds = redisTemplate.opsForZSet().rangeByScore(
                RUNNING_WORKFLOWS_KEY,
                0,
                now.toEpochMilli(),
                0,
                limit
        );
        List<String> expiredWorkflowIds = workflowIds == null || workflowIds.isEmpty()
                ? List.of()
                : List.copyOf(workflowIds);
        if (!expiredWorkflowIds.isEmpty()) {
            log.info("workflow_recovery_candidates count={} ids={}", expiredWorkflowIds.size(), expiredWorkflowIds);
        }
        return expiredWorkflowIds;
    }

    public Optional<WorkflowMetadata> tryClaimExpired(String workflowId) {
        Instant now = Instant.now(clock);
        Instant leaseUntil = leaseUntil(now);
        Long result = redisTemplate.execute(
                CLAIM_EXPIRED_SCRIPT,
                List.of(workflowKey(workflowId), RUNNING_WORKFLOWS_KEY),
                Long.toString(now.toEpochMilli()),
                ownerId,
                leaseUntil.toString(),
                Long.toString(leaseUntil.toEpochMilli()),
                now.toString(),
                workflowId,
                Long.toString(WORKFLOW_TTL.toSeconds())
        );
        if (result == null || result != 1L) {
            log.debug("workflow_claim_skipped workflowId={} ownerId={} result={}", workflowId, ownerId, result);
            return Optional.empty();
        }
        log.info("workflow_claimed workflowId={} ownerId={} leaseUntil={}", workflowId, ownerId, leaseUntil);
        return Optional.ofNullable(readWorkflow(workflowId));
    }

    public Lease renewLeaseUntilClosed(WorkflowMetadata workflow) {
        if (workflow == null || (workflow.status() != WorkflowStatus.RUNNING
                && workflow.status() != WorkflowStatus.RECOVERING)) {
            return Lease.noop();
        }
        AtomicReference<ScheduledFuture<?>> renewal = new AtomicReference<>();
        ScheduledFuture<?> future = leaseRenewalExecutor.scheduleAtFixedRate(
                () -> {
                    if (!renewLeaseSafely(workflow)) {
                        ScheduledFuture<?> scheduled = renewal.get();
                        if (scheduled != null) {
                            scheduled.cancel(false);
                        }
                    }
                },
                workflowLeaseRenewalInterval.toMillis(),
                workflowLeaseRenewalInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
        renewal.set(future);
        return new Lease(future);
    }

    public boolean renewLease(WorkflowMetadata workflow) {
        Instant now = Instant.now(clock);
        Instant leaseUntil = leaseUntil(now);
        Long result = redisTemplate.execute(
                RENEW_LEASE_SCRIPT,
                List.of(workflowKey(workflow.workflowId()), RUNNING_WORKFLOWS_KEY),
                workflow.ownerId(),
                Long.toString(workflow.leaseVersion()),
                leaseUntil.toString(),
                Long.toString(leaseUntil.toEpochMilli()),
                now.toString(),
                workflow.workflowId(),
                Long.toString(WORKFLOW_TTL.toSeconds())
        );
        if (result != null && result == 1L) {
            log.debug(
                    "workflow_lease_renewed workflowId={} ownerId={} leaseVersion={} leaseUntil={}",
                    workflow.workflowId(),
                    workflow.ownerId(),
                    workflow.leaseVersion(),
                    leaseUntil
            );
            return true;
        }
        log.warn(
                "workflow_lease_renewal_rejected workflowId={} ownerId={} leaseVersion={} result={}",
                workflow.workflowId(),
                workflow.ownerId(),
                workflow.leaseVersion(),
                result
        );
        return false;
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
                workflow.ownerId(),
                workflow.leaseUntil(),
                workflow.leaseVersion(),
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

    public Optional<ExecutionLock> tryAcquireExecutionLock(String idempotencyKey) {
        String normalizedKey = blankToNull(idempotencyKey);
        if (normalizedKey == null) {
            return Optional.empty();
        }

        String lockKey = executionLockKey(normalizedKey);
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, WORKFLOW_EXECUTION_LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            log.info("workflow_execution_lock_acquired idempotencyKey={}", normalizedKey);
            return Optional.of(new ExecutionLock(() -> releaseExecutionLock(lockKey, token)));
        }

        log.info("workflow_execution_lock_busy idempotencyKey={}", normalizedKey);
        return Optional.empty();
    }

    public Optional<IdempotentWorkflow> completedIdempotentWorkflow(String idempotencyKey) {
        String normalizedKey = blankToNull(idempotencyKey);
        if (normalizedKey == null) {
            return Optional.empty();
        }

        Map<Object, Object> fields = redisTemplate.opsForHash().entries(idempotencyKey(normalizedKey));
        if (fields.isEmpty() || !"COMPLETED".equals(value(fields, "status"))) {
            return Optional.empty();
        }

        String workflowId = value(fields, "workflowId");
        if (workflowId.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new IdempotentWorkflow(
                workflowId,
                value(fields, "conversationId"),
                value(fields, "status")
        ));
    }

    public void recordCompletedIdempotentWorkflow(
            String idempotencyKey,
            String workflowId,
            String conversationId,
            WorkflowStatus status
    ) {
        String normalizedKey = blankToNull(idempotencyKey);
        if (normalizedKey == null || workflowId == null || workflowId.isBlank()) {
            return;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "idempotencyKey", normalizedKey);
        put(fields, "workflowId", workflowId);
        put(fields, "conversationId", conversationId);
        put(fields, "status", status == null ? null : status.name());
        put(fields, "completedAt", Instant.now(clock));

        String key = idempotencyKey(normalizedKey);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForHash().putAll(key, fields);
                operations.expire(key, WORKFLOW_TTL);
                return null;
            }
        });
        log.info(
                "workflow_idempotency_recorded idempotencyKey={} workflowId={} status={}",
                normalizedKey,
                workflowId,
                status
        );
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

    public List<Map<String, String>> workflowEvents(String workflowId, int limit) {
        if (workflowId == null || workflowId.isBlank()) {
            return List.of();
        }

        int eventLimit = Math.max(1, limit);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().reverseRange(
                WorkflowEventService.streamKey(workflowId),
                Range.unbounded(),
                Limit.limit().count(eventLimit)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        return records.reversed().stream()
                .map(MapRecord::getValue)
                .map(this::stringMap)
                .toList();
    }

    public List<String> sessionWorkflowIds(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<String> workflowIds = redisTemplate.opsForList().range(sessionWorkflowsKey(sessionId), 0, -1);
        return workflowIds == null ? List.of() : List.copyOf(workflowIds);
    }

    private Map<String, String> stringMap(Map<Object, Object> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key != null && value != null) {
                values.put(key.toString(), value.toString());
            }
        });
        return values;
    }

    @PreDestroy
    void stopLeaseRenewalExecutor() {
        leaseRenewalExecutor.shutdownNow();
    }

    private void releaseExecutionLock(String lockKey, String token) {
        Long result = redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        log.debug("workflow_execution_lock_released lockKey={} result={}", lockKey, result);
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
                workflow.ownerId(),
                workflow.leaseUntil(),
                workflow.leaseVersion(),
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
        Long result = redisTemplate.execute(
                TERMINAL_SCRIPT,
                List.of(workflowKey(metadata.workflowId()), RUNNING_WORKFLOWS_KEY),
                metadata.ownerId(),
                Long.toString(metadata.leaseVersion()),
                metadata.status().name(),
                metadata.updatedAt().toString(),
                metadata.finishedAt().toString(),
                metadata.failureReason() == null ? "" : metadata.failureReason(),
                blankToNull(recoveredByWorkflowId) == null ? "" : recoveredByWorkflowId,
                metadata.workflowId(),
                Long.toString(WORKFLOW_TTL.toSeconds())
        );
        if (result != null && result < 1L) {
            log.warn(
                    "workflow_terminal_rejected workflowId={} status={} ownerId={} leaseVersion={} result={}",
                    metadata.workflowId(),
                    metadata.status(),
                    metadata.ownerId(),
                    metadata.leaseVersion(),
                    result
            );
            throw new WorkflowOwnershipException(metadata.workflowId(), result);
        }
        log.info(
                "workflow_terminal workflowId={} status={} ownerId={} leaseVersion={} recoveredByWorkflowId={} failureReasonPresent={}",
                metadata.workflowId(),
                metadata.status(),
                metadata.ownerId(),
                metadata.leaseVersion(),
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
                operations.opsForZSet().remove(RUNNING_WORKFLOWS_KEY, metadata.workflowId());
                operations.expire(key, WORKFLOW_TTL);
                operations.expire(RUNNING_WORKFLOWS_KEY, WORKFLOW_TTL);
                return null;
            }
        });
        log.info(
                "workflow_waiting_for_approval workflowId={} approvalId={} ownerId={} leaseVersion={}",
                metadata.workflowId(),
                fields.getOrDefault("approvalId", ""),
                metadata.ownerId(),
                metadata.leaseVersion()
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
        put(fields, "ownerId", metadata.ownerId());
        put(fields, "leaseUntil", metadata.leaseUntil());
        put(fields, "leaseUntilEpochMs", epochMillis(metadata.leaseUntil()));
        put(fields, "leaseVersion", metadata.leaseVersion());
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
                if (startedWorkflow != null && startedWorkflow.leaseUntil() != null) {
                    operations.opsForZSet().add(
                            RUNNING_WORKFLOWS_KEY,
                            startedWorkflow.workflowId(),
                            epochMillis(startedWorkflow.leaseUntil())
                    );
                    operations.expire(RUNNING_WORKFLOWS_KEY, WORKFLOW_TTL);
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

    private void indexUserWorkflow(String userId, String workflowId) {
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                indexUserWorkflow(operations, userId, workflowId);
                return null;
            }
        });
    }

    private void indexUserConversation(String userId, String conversationId) {
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                indexUserConversation(operations, userId, conversationId);
                return null;
            }
        });
    }

    private void indexConversationWorkflow(String conversationId, String workflowId) {
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                indexConversationWorkflow(operations, conversationId, workflowId);
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
                value(fields, "ownerId"),
                instant(value(fields, "leaseUntil")),
                longValue(value(fields, "leaseVersion"), 1L),
                integer(value(fields, "attempt"), 1),
                instant(value(fields, "createdAt")),
                instant(value(fields, "updatedAt")),
                instant(value(fields, "finishedAt")),
                blankToNull(value(fields, "failureReason"))
        );
    }

    private boolean renewLeaseSafely(WorkflowMetadata workflow) {
        try {
            if (!renewLease(workflow)) {
                log.warn("Stopped renewing workflow {} because ownership changed.", workflow.workflowId());
                return false;
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to renew workflow {} lease: {}", workflow.workflowId(), ex.getMessage());
        }
        return true;
    }

    private Instant leaseUntil(Instant now) {
        return now.plus(workflowLease);
    }

    private long epochMillis(Instant instant) {
        return instant == null ? 0 : instant.toEpochMilli();
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

    private Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private Long longValue(String value, Long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Long.parseLong(value);
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

        private final ScheduledFuture<?> future;

        private Lease(ScheduledFuture<?> future) {
            this.future = future;
        }

        static Lease noop() {
            return new Lease(null);
        }

        @Override
        public void close() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    public static final class ExecutionLock implements AutoCloseable {

        private final Runnable release;
        private boolean closed;

        private ExecutionLock(Runnable release) {
            this.release = release;
        }

        public static ExecutionLock noop() {
            return new ExecutionLock(() -> {
            });
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                release.run();
            }
        }
    }

    public record IdempotentWorkflow(
            String workflowId,
            String conversationId,
            String status
    ) {
    }

    public static class WorkflowOwnershipException extends RuntimeException {

        WorkflowOwnershipException(String workflowId, long result) {
            super("Workflow %s is no longer owned by this worker. Redis result: %d.".formatted(workflowId, result));
        }
    }
}
