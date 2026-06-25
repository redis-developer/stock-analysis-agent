package com.redis.stockanalysisagent.workflow.checkpoint;

import com.redis.stockanalysisagent.workflow.events.WorkflowEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkflowCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCheckpointService.class);
    private static final int MAX_RECOVERED_EVIDENCE_CHARS = 20_000;

    private final StringRedisTemplate redisTemplate;

    public WorkflowCheckpointService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<WorkflowCheckpoint> latestCheckpoint(String workflowId) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().reverseRange(
                WorkflowEventService.checkpointsKey(workflowId),
                Range.unbounded(),
                Limit.limit().count(1)
        );
        if (records == null || records.isEmpty()) {
            log.info("workflow_checkpoint_missing workflowId={}", workflowId);
            return Optional.empty();
        }
        MapRecord<String, Object, Object> record = records.getFirst();
        Map<Object, Object> values = record.getValue();
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(
                record.getId().getValue(),
                timestamp(values, record.getId().getTimestamp()),
                value(values, "checkpointId"),
                value(values, "stepId"),
                value(values, "sourceEventType"),
                value(values, "actorType"),
                value(values, "actorName"),
                value(values, "summary"),
                value(values, "inputBytes"),
                value(values, "inputPayload"),
                value(values, "outputBytes"),
                value(values, "outputPayload")
        );
        log.info(
                "workflow_checkpoint_loaded workflowId={} checkpointId={} stepId={} actorType={} actorName={} inputBytes={} outputBytes={}",
                workflowId,
                checkpoint.checkpointId(),
                checkpoint.stepId(),
                checkpoint.actorType(),
                checkpoint.actorName(),
                checkpoint.inputBytes(),
                checkpoint.outputBytes()
        );
        return Optional.of(checkpoint);
    }

    public Optional<String> originalUserMessage(String workflowId) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(WorkflowEventService.streamKey(workflowId), Range.unbounded());
        if (records == null || records.isEmpty()) {
            log.info("workflow_original_message_missing workflowId={}", workflowId);
            return Optional.empty();
        }
        Optional<String> message = records.stream()
                .map(MapRecord::getValue)
                .filter(values -> "REQUEST_ANALYSIS".equals(value(values, "stepId")))
                .map(values -> value(values, "inputPayload"))
                .filter(value -> !value.isBlank())
                .findFirst();
        message.ifPresentOrElse(
                value -> log.info("workflow_original_message_loaded workflowId={} bytes={}", workflowId, value.length()),
                () -> log.info("workflow_original_message_missing workflowId={}", workflowId)
        );
        return message;
    }

    public String replayMessage(String workflowId, Map<Object, Object> metadata, WorkflowCheckpoint checkpoint) {
        StringBuilder message = new StringBuilder();
        message.append("Continue this stock analysis workflow from the latest Redis checkpoint.\n\n");
        appendLine(message, "Original workflow", workflowId);
        appendLine(message, "Conversation", value(metadata, "conversationId"));
        appendLine(message, "Resume step", checkpoint.stepId());
        appendLine(message, "Replay checkpoint", checkpoint.checkpointId());
        appendLine(message, "Checkpoint actor", actor(checkpoint));
        appendBlock(message, "Recovered evidence", recoveredEvidence(workflowId, checkpoint));
        message.append("\nCheckpoint summary:\n").append(checkpoint.summary()).append('\n');
        appendBlock(message, "Checkpoint input", replaySafePayload(checkpoint.inputPayload()));
        if (isTemporaryProviderFailure(checkpoint.outputPayload())) {
            appendBlock(
                    message,
                    "Checkpoint output",
                    "Previous checkpoint output reported a temporary provider failure. Treat that specialist evidence as missing and retry the tool if it is still needed."
            );
        } else {
            appendBlock(message, "Checkpoint output", checkpoint.outputPayload());
        }
        message.append("""

                Recovery instructions:
                1. Treat successful recovered evidence as committed specialist output from work that already completed before the checkpoint.
                2. Do not call specialist tools for successful evidence that is already present.
                3. Provider outage, unavailable, rate limit, timeout, or failed retrieval messages are not successful evidence.
                4. Retry missing specialist tools when their previous output only reported a temporary provider failure.
                5. If successful recovered evidence is enough, answer directly from it.
                6. Use the checkpoint as committed context and continue from that point.
                """);
        return message.toString();
    }

    private String recoveredEvidence(String workflowId, WorkflowCheckpoint checkpoint) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(WorkflowEventService.streamKey(workflowId), Range.unbounded());
        if (records == null || records.isEmpty()) {
            return "";
        }

        List<String> evidence = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> values = record.getValue();
            if (isEvidenceEvent(values)) {
                evidence.add(evidenceEntry(values));
            }
            if (checkpoint.checkpointId().equals(value(values, "checkpointId"))) {
                break;
            }
        }

        if (evidence.isEmpty()) {
            return "";
        }

        String text = String.join("\n\n", evidence);
        if (text.length() <= MAX_RECOVERED_EVIDENCE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_RECOVERED_EVIDENCE_CHARS) + "\n\n[Recovered evidence truncated.]";
    }

    private boolean isEvidenceEvent(Map<Object, Object> values) {
        String outputPayload = value(values, "outputPayload");
        if (!"completed".equals(value(values, "status")) || outputPayload.isBlank()) {
            return false;
        }

        if (isTemporaryProviderFailure(outputPayload)) {
            return false;
        }

        if ("agent".equals(value(values, "kind")) && "sub_agent".equals(value(values, "actorType"))) {
            return true;
        }

        String toolName = value(values, "toolName");
        String actorName = value(values, "actorName");
        return !toolName.isBlank()
                && !"coordinator".equals(actorName)
                && !"system".equals(actorName);
    }

    private String evidenceEntry(Map<Object, Object> values) {
        StringBuilder entry = new StringBuilder();
        entry.append(evidenceLabel(values)).append('\n');
        appendLine(entry, "Step", value(values, "stepId"));
        appendLine(entry, "Actor", actor(values));
        appendLine(entry, "Input", value(values, "inputPayload"));
        appendLine(entry, "Output", value(values, "outputPayload"));
        return entry.toString().trim();
    }

    private String evidenceLabel(Map<Object, Object> values) {
        String toolName = value(values, "toolName");
        if (!toolName.isBlank()) {
            return "Tool evidence: " + toolName;
        }

        String actorName = actor(values);
        return actorName.isBlank() ? "Specialist evidence" : "Specialist evidence: " + actorName;
    }

    private String replaySafePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        if (!payload.startsWith("Continue this stock analysis workflow from the latest Redis checkpoint.")) {
            return payload;
        }

        return extractOriginalCheckpointInput(payload);
    }

    private String extractOriginalCheckpointInput(String payload) {
        String current = payload;
        String candidate = "";
        while (current != null && !current.isBlank()) {
            int start = current.indexOf("\nCheckpoint input:\n");
            if (start < 0) {
                break;
            }
            start += "\nCheckpoint input:\n".length();
            int end = current.indexOf("\nCheckpoint output:", start);
            if (end < 0) {
                end = current.indexOf("\nRecovery instructions:", start);
            }
            String block = (end < 0 ? current.substring(start) : current.substring(start, end)).trim();
            if (!block.startsWith("Continue this stock analysis workflow from the latest Redis checkpoint.")) {
                candidate = block;
            }
            current = block;
        }
        return candidate;
    }

    private boolean isTemporaryProviderFailure(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("provider outage")
                || normalized.contains("data provider outage")
                || normalized.contains("unable to retrieve")
                || normalized.contains("currently unable")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("temporary service outage")
                || normalized.contains("service outage")
                || normalized.contains("rate limit")
                || normalized.contains("timed out")
                || normalized.contains("timeout");
    }

    private String actor(WorkflowCheckpoint checkpoint) {
        if (!checkpoint.actorName().isBlank()) {
            return checkpoint.actorName();
        }
        return checkpoint.actorType();
    }

    private String actor(Map<Object, Object> values) {
        String actorName = value(values, "actorName");
        return actorName.isBlank() ? value(values, "actorType") : actorName;
    }

    private void appendLine(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append(label).append(": ").append(value).append('\n');
        }
    }

    private void appendBlock(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append('\n').append(label).append(":\n").append(value).append('\n');
        }
    }

    private Instant timestamp(Map<Object, Object> values, long fallbackMillis) {
        String value = value(values, "timestamp");
        if (value.isBlank()) {
            return Instant.ofEpochMilli(fallbackMillis);
        }
        return Instant.parse(value);
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }
}
