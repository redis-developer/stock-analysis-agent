package com.redis.stockanalysisagent.workflow.events;

import com.redis.stockanalysisagent.chat.ChatProgressStep;
import com.redis.stockanalysisagent.chat.ChatProgressMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowEventService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventService.class);

    public static final String EVENTS_SUFFIX = ":events";
    public static final String CHECKPOINTS_SUFFIX = ":checkpoints";

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Autowired
    public WorkflowEventService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC());
    }

    WorkflowEventService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public void appendProgressEvent(String workflowId, ChatProgressStep step) {
        if (workflowId == null || workflowId.isBlank()) {
            return;
        }
        append(workflowId, step, Instant.now(clock));
    }

    public List<Map<String, String>> events(String workflowId, int limit) {
        if (workflowId == null || workflowId.isBlank()) {
            return List.of();
        }

        int eventLimit = Math.max(1, limit);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().reverseRange(
                streamKey(workflowId),
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

    public static String streamKey(String workflowId) {
        return WorkflowService.workflowKey(workflowId) + EVENTS_SUFFIX;
    }

    public static String checkpointsKey(String workflowId) {
        return WorkflowService.workflowKey(workflowId) + CHECKPOINTS_SUFFIX;
    }

    private void append(String workflowId, ChatProgressStep step, Instant timestamp) {
        Map<String, String> fields = fields(workflowId, step, timestamp);
        Map<String, String> checkpoint = checkpointFields(workflowId, step, fields, timestamp);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                String key = streamKey(workflowId);
                operations.opsForStream().add(key, fields);
                if (checkpoint != null) {
                    String checkpointKey = checkpointsKey(workflowId);
                    operations.opsForStream().add(checkpointKey, checkpoint);
                    operations.expire(checkpointKey, WorkflowService.workflowTtl());
                    operations.opsForStream().add(key, checkpointEventFields(workflowId, checkpoint, timestamp));
                }
                operations.expire(key, WorkflowService.workflowTtl());
                return null;
            }
        });
        log.info(
                "workflow_event_appended workflowId={} stepId={} status={} kind={} actorType={} actorName={} checkpoint={}",
                workflowId,
                step.id(),
                step.status(),
                step.kind(),
                step.actorType(),
                step.actorName(),
                checkpoint != null
        );
        if (checkpoint != null) {
            log.info(
                    "workflow_checkpoint_created workflowId={} checkpointId={} stepId={} actorType={} actorName={}",
                    workflowId,
                    checkpoint.getOrDefault("checkpointId", ""),
                    checkpoint.getOrDefault("stepId", ""),
                    checkpoint.getOrDefault("actorType", ""),
                    checkpoint.getOrDefault("actorName", "")
            );
        }
    }

    private Map<String, String> fields(String workflowId, ChatProgressStep step, Instant timestamp) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workflowId", workflowId);
        fields.put("eventType", eventType(step));
        fields.put("stepId", step.id());
        fields.put("status", value(step.status()));
        fields.put("kind", value(step.kind()));
        fields.put("actorType", value(step.actorType()));
        fields.put("actorName", value(step.actorName()));
        fields.put("summary", value(step.summary()));
        fields.put("durationMs", step.durationMs() == null ? "" : step.durationMs().toString());
        appendMetadata(fields, step.metadata());
        fields.put("timestamp", timestamp.toString());
        return fields;
    }

    private Map<String, String> checkpointFields(
            String workflowId,
            ChatProgressStep step,
            Map<String, String> eventFields,
            Instant timestamp
    ) {
        if (!isCheckpointable(step)) {
            return null;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workflowId", workflowId);
        fields.put("eventType", "checkpoint.created");
        fields.put("checkpointId", checkpointId(step, timestamp));
        fields.put("sourceEventType", eventFields.getOrDefault("eventType", ""));
        fields.put("stepId", step.id());
        fields.put("status", value(step.status()));
        fields.put("kind", value(step.kind()));
        fields.put("actorType", value(step.actorType()));
        fields.put("actorName", value(step.actorName()));
        fields.put("summary", value(step.summary()));
        fields.put("durationMs", step.durationMs() == null ? "" : step.durationMs().toString());
        fields.put("timestamp", timestamp.toString());
        appendMetadata(fields, step.metadata());
        return fields;
    }

    private Map<String, String> checkpointEventFields(
            String workflowId,
            Map<String, String> checkpoint,
            Instant timestamp
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workflowId", workflowId);
        fields.put("eventType", "checkpoint.created");
        fields.put("stepId", "checkpoint:" + checkpoint.getOrDefault("stepId", ""));
        fields.put("status", "completed");
        fields.put("kind", "checkpoint");
        fields.put("actorType", checkpoint.getOrDefault("actorType", ""));
        fields.put("actorName", checkpoint.getOrDefault("actorName", ""));
        fields.put("summary", "Created checkpoint for " + checkpoint.getOrDefault("stepId", "") + ".");
        fields.put("checkpointId", checkpoint.getOrDefault("checkpointId", ""));
        fields.put("checkpointedStepId", checkpoint.getOrDefault("stepId", ""));
        fields.put("checkpointedEventType", checkpoint.getOrDefault("sourceEventType", ""));
        fields.put("timestamp", timestamp.toString());
        return fields;
    }

    private boolean isCheckpointable(ChatProgressStep step) {
        if (!"completed".equals(step.status())) {
            return false;
        }
        return "agent".equals(step.kind()) || (step.metadata() != null && !step.metadata().toolName().isBlank());
    }

    private String checkpointId(ChatProgressStep step, Instant timestamp) {
        String actor = step.actorName() == null || step.actorName().isBlank() ? step.actorType() : step.actorName();
        return step.id() + ":" + actor + ":" + timestamp.toEpochMilli();
    }

    private String eventType(ChatProgressStep step) {
        if (step.metadata() == null || step.metadata().toolName().isBlank()) {
            return "progress";
        }
        if ("completed".equals(step.status())) {
            return "tool.completed";
        }
        if ("failed".equals(step.status())) {
            return "tool.failed";
        }
        return "tool.requested";
    }

    private void appendMetadata(Map<String, String> fields, ChatProgressMetadata metadata) {
        if (metadata == null || !metadata.hasFields()) {
            return;
        }

        putIfPresent(fields, "toolName", metadata.toolName());
        putIfPresent(fields, "inputHash", metadata.inputHash());
        putIfPresent(fields, "inputBytes", metadata.inputBytes());
        putIfPresent(fields, "inputPayload", metadata.inputPayload());
        putIfPresent(fields, "outputHash", metadata.outputHash());
        putIfPresent(fields, "outputBytes", metadata.outputBytes());
        putIfPresent(fields, "outputPayload", metadata.outputPayload());
        putIfPresent(fields, "errorType", metadata.errorType());
    }

    private void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }

    private void putIfPresent(Map<String, String> fields, String key, Long value) {
        if (value != null) {
            fields.put(key, value.toString());
        }
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

    private String value(String value) {
        return value == null ? "" : value;
    }
}
