package com.redis.stockanalysisagent.workflow.execution;

import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class WorkflowQueueService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowQueueService.class);

    public static final String RECOVERY_STREAM_KEY = "stock-analysis:workflow-recovery";
    static final String RECOVERY_GROUP = "workflow-recovery";
    static final Duration DEFAULT_PENDING_MIN_IDLE = Duration.ofSeconds(15);

    private final StringRedisTemplate redisTemplate;
    private final String consumerName;
    private final Duration pendingMinIdle;

    @Autowired
    public WorkflowQueueService(
            StringRedisTemplate redisTemplate,
            @Value("${stock-analysis.workflow.queue.pending-min-idle:15s}") Duration pendingMinIdle
    ) {
        this(redisTemplate, () -> UUID.randomUUID().toString(), pendingMinIdle);
    }

    WorkflowQueueService(
            StringRedisTemplate redisTemplate,
            Supplier<String> consumerNameSupplier,
            Duration pendingMinIdle
    ) {
        this.redisTemplate = redisTemplate;
        this.consumerName = consumerNameSupplier.get();
        this.pendingMinIdle = pendingMinIdle == null ? DEFAULT_PENDING_MIN_IDLE : pendingMinIdle;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ensureRecoveryGroupOnStartup() {
        ensureRecoveryGroup();
    }

    public List<QueuedWorkflow> claimRecoverableWorkflows(int limit) {
        ensureRecoveryGroup();
        List<MapRecord<String, Object, Object>> records = new ArrayList<>();
        records.addAll(claimPending(limit));
        int remaining = limit - records.size();
        if (remaining > 0) {
            records.addAll(readNew(remaining));
        }

        List<QueuedWorkflow> workflows = records.stream()
                .map(this::queuedWorkflow)
                .filter(workflow -> workflow.workflowId() != null && !workflow.workflowId().isBlank())
                .toList();
        if (!workflows.isEmpty()) {
            log.info(
                    "workflow_recovery_queue_claimed count={} workflowIds={}",
                    workflows.size(),
                    workflows.stream().map(QueuedWorkflow::workflowId).toList()
            );
        }
        return workflows;
    }

    public void ack(QueuedWorkflow workflow) {
        redisTemplate.opsForStream().acknowledge(RECOVERY_STREAM_KEY, RECOVERY_GROUP, workflow.streamId());
        log.debug(
                "workflow_recovery_queue_acked workflowId={} streamId={}",
                workflow.workflowId(),
                workflow.streamId()
        );
    }

    public static Map<String, String> recoveryFields(WorkflowMetadata workflow) {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "workflowId", workflow.workflowId());
        put(fields, "userId", workflow.userId());
        put(fields, "sessionId", workflow.sessionId());
        put(fields, "conversationId", workflow.conversationId());
        put(fields, "status", workflow.status().name());
        put(fields, "attempt", workflow.attempt());
        put(fields, "createdAt", workflow.createdAt());
        return fields;
    }

    private List<MapRecord<String, Object, Object>> readNew(int limit) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(RECOVERY_GROUP, consumerName),
                StreamReadOptions.empty().count(limit),
                StreamOffset.create(RECOVERY_STREAM_KEY, ReadOffset.lastConsumed())
        );
        return records == null ? List.of() : records;
    }

    private List<MapRecord<String, Object, Object>> claimPending(int limit) {
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(RECOVERY_STREAM_KEY, RECOVERY_GROUP, Range.unbounded(), limit, pendingMinIdle);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }

        List<RecordId> recordIds = new ArrayList<>();
        for (PendingMessage message : pending) {
            recordIds.add(message.getId());
        }
        if (recordIds.isEmpty()) {
            return List.of();
        }

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().claim(
                RECOVERY_STREAM_KEY,
                RECOVERY_GROUP,
                consumerName,
                pendingMinIdle,
                recordIds.toArray(RecordId[]::new)
        );
        return records == null ? List.of() : records;
    }

    private QueuedWorkflow queuedWorkflow(MapRecord<String, Object, Object> record) {
        Object workflowId = record.getValue().get("workflowId");
        return new QueuedWorkflow(record.getId().getValue(), workflowId == null ? "" : workflowId.toString());
    }

    private void ensureRecoveryGroup() {
        try {
            redisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
                    RedisSerializer.string().serialize(RECOVERY_STREAM_KEY),
                    RECOVERY_GROUP,
                    ReadOffset.from("0-0"),
                    true
            ));
        } catch (DataAccessException ex) {
            if (isBusyGroup(ex)) {
                return;
            }
            throw ex;
        }
    }

    private boolean isBusyGroup(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void put(Map<String, String> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value.toString());
        }
    }

    public record QueuedWorkflow(
            String streamId,
            String workflowId
    ) {
    }
}
