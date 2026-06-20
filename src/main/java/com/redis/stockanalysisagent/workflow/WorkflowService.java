package com.redis.stockanalysisagent.workflow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
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
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class WorkflowService {

    static final String KEY_PREFIX = "stock-analysis:workflows:";
    static final String SESSION_KEY_PREFIX = "stock-analysis:sessions:";
    static final String SESSION_WORKFLOWS_SUFFIX = ":workflows";
    static final Duration WORKFLOW_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final Supplier<String> workflowIdSupplier;

    @Autowired
    public WorkflowService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    WorkflowService(StringRedisTemplate redisTemplate, Clock clock, Supplier<String> workflowIdSupplier) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.workflowIdSupplier = workflowIdSupplier;
    }

    public WorkflowMetadata start(
            String userId,
            String sessionId,
            String conversationId,
            String clientRequestId
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
                now,
                now,
                null,
                null
        );
        writeStarted(metadata);
        return metadata;
    }

    public WorkflowMetadata complete(WorkflowMetadata workflow) {
        return terminal(workflow, WorkflowStatus.COMPLETED, null);
    }

    public WorkflowMetadata fail(WorkflowMetadata workflow, RuntimeException failure) {
        return terminal(workflow, WorkflowStatus.FAILED, errorMessage(failure));
    }

    void writeStarted(WorkflowMetadata metadata) {
        writePipelined(metadata.workflowId(), allFields(metadata), metadata);
    }

    void writeTerminal(WorkflowMetadata metadata) {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "status", metadata.status().name());
        put(fields, "updatedAt", metadata.updatedAt());
        put(fields, "finishedAt", metadata.finishedAt());
        put(fields, "failureReason", metadata.failureReason());
        writePipelined(metadata.workflowId(), fields);
    }

    static String workflowKey(String workflowId) {
        return KEY_PREFIX + workflowId;
    }

    static String sessionWorkflowsKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId + SESSION_WORKFLOWS_SUFFIX;
    }

    static Duration workflowTtl() {
        return WORKFLOW_TTL;
    }

    private WorkflowMetadata terminal(WorkflowMetadata workflow, WorkflowStatus status, String failureReason) {
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
                workflow.createdAt(),
                now,
                now,
                failureReason
        );
        writeTerminal(metadata);
        return metadata;
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
        put(fields, "createdAt", metadata.createdAt());
        put(fields, "updatedAt", metadata.updatedAt());
        put(fields, "finishedAt", metadata.finishedAt());
        put(fields, "failureReason", metadata.failureReason());
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
                if (startedWorkflow != null && startedWorkflow.sessionId() != null && !startedWorkflow.sessionId().isBlank()) {
                    String sessionKey = sessionWorkflowsKey(startedWorkflow.sessionId());
                    operations.opsForList().rightPush(sessionKey, startedWorkflow.workflowId());
                    operations.expire(sessionKey, WORKFLOW_TTL);
                }
                return null;
            }
        });
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
}
