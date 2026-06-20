package com.redis.stockanalysisagent.workflow;

import com.redis.stockanalysisagent.chat.ChatProgressStep;
import com.redis.stockanalysisagent.chat.ChatProgressMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WorkflowEventService {

    private static final String EVENTS_SUFFIX = ":events";

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

    static String streamKey(String workflowId) {
        return WorkflowService.workflowKey(workflowId) + EVENTS_SUFFIX;
    }

    private void append(String workflowId, ChatProgressStep step, Instant timestamp) {
        Map<String, String> fields = fields(workflowId, step, timestamp);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                String key = streamKey(workflowId);
                operations.opsForStream().add(key, fields);
                operations.expire(key, WorkflowService.workflowTtl());
                return null;
            }
        });
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

    private String value(String value) {
        return value == null ? "" : value;
    }
}
