package com.redis.stockanalysisagent.instrumentation;

import com.redis.stockanalysisagent.chat.ChatProgressMetadata;
import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.workflow.ApprovalRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
@SuppressWarnings("deprecation")
public class ToolCallInstrumentation {

    private static final Logger log = LoggerFactory.getLogger(ToolCallInstrumentation.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final int MAX_SUMMARY_FIELDS = 6;

    private final ChatProgressPublisher progressPublisher;
    private final AtomicLong invocationSequence = new AtomicLong();

    public ToolCallInstrumentation(ChatProgressPublisher progressPublisher) {
        this.progressPublisher = progressPublisher;
    }

    public Object[] callbacks(String actorType, String actorName, Object toolObject) {
        ToolCallback[] callbacks = ToolCallbacks.from(toolObject);
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = new InstrumentedToolCallback(callbacks[i], actorType, actorName);
        }
        return wrapped;
    }

    private final class InstrumentedToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final String actorType;
        private final String actorName;

        private InstrumentedToolCallback(ToolCallback delegate, String actorType, String actorName) {
            this.delegate = delegate;
            this.actorType = actorType;
            this.actorName = actorName;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return instrument(toolInput, () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return instrument(toolInput, () -> delegate.call(toolInput, toolContext));
        }

        private String instrument(String toolInput, Supplier<String> action) {
            ToolDefinition definition = getToolDefinition();
            String toolName = definition.name();
            String stepId = "tool:" + toolName + ":" + invocationSequence.incrementAndGet();
            String label = "Tool " + toolName;
            String argumentSummary = argumentSummary(toolInput);
            String summarySuffix = summarySuffix(argumentSummary);
            long startedAt = System.nanoTime();
            Long inputBytes = sizeBytes(toolInput);
            log.info(
                    "tool_call_started stepId={} toolName={} actorType={} actorName={} inputBytes={}",
                    stepId,
                    toolName,
                    actorType,
                    actorName,
                    inputBytes
            );

            progressPublisher.running(
                    stepId,
                    label,
                    ChatProgressPublisher.KIND_SYSTEM,
                    "Calling tool " + toolName + summarySuffix + ".",
                    actorType,
                    actorName,
                    metadata(toolName, toolInput, null, "")
            );

            try {
                String result = action.get();
                long durationMs = elapsedDurationMs(startedAt);
                log.info(
                        "tool_call_completed stepId={} toolName={} actorType={} actorName={} durationMs={} outputBytes={}",
                        stepId,
                        toolName,
                        actorType,
                        actorName,
                        durationMs,
                        sizeBytes(result)
                );
                progressPublisher.completed(
                        stepId,
                        label,
                        ChatProgressPublisher.KIND_SYSTEM,
                        durationMs,
                        "Completed tool " + toolName + summarySuffix + ".",
                        actorType,
                        actorName,
                        metadata(toolName, toolInput, result, "")
                );
                return result;
            } catch (RuntimeException ex) {
                long durationMs = elapsedDurationMs(startedAt);
                ApprovalRequiredException approvalRequired = approvalRequired(ex);
                if (approvalRequired != null) {
                    log.info(
                            "tool_call_waiting_for_approval stepId={} toolName={} actorType={} actorName={} durationMs={} approvalId={}",
                            stepId,
                            toolName,
                            actorType,
                            actorName,
                            durationMs,
                            approvalRequired.approval().approvalId()
                    );
                    progressPublisher.completed(
                            stepId,
                            label,
                            ChatProgressPublisher.KIND_SYSTEM,
                            durationMs,
                            "Paused tool " + toolName + summarySuffix + " until approval.",
                            actorType,
                            actorName,
                            metadata(toolName, toolInput, approvalRequired.getMessage(), "")
                    );
                    throw ex;
                }
                log.warn(
                        "tool_call_failed stepId={} toolName={} actorType={} actorName={} durationMs={} error={}",
                        stepId,
                        toolName,
                        actorType,
                        actorName,
                        durationMs,
                        rootCauseType(ex),
                        ex
                );
                progressPublisher.failed(
                        stepId,
                        label,
                        ChatProgressPublisher.KIND_SYSTEM,
                        durationMs,
                        "Failed tool " + toolName + summarySuffix + ": " + rootCauseType(ex) + ".",
                        actorType,
                        actorName,
                        metadata(toolName, toolInput, null, rootCauseType(ex))
                );
                throw ex;
            }
        }
    }

    private ApprovalRequiredException approvalRequired(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ApprovalRequiredException approvalRequired) {
                return approvalRequired;
            }
            current = current.getCause();
        }
        return null;
    }

    private ChatProgressMetadata metadata(
            String toolName,
            String inputPayload,
            String outputPayload,
            String errorType
    ) {
        return new ChatProgressMetadata(
                toolName,
                hash(inputPayload),
                sizeBytes(inputPayload),
                inputPayload,
                hash(outputPayload),
                sizeBytes(outputPayload),
                outputPayload,
                errorType
        );
    }

    private String hash(String value) {
        if (value == null) {
            return "";
        }
        try {
            return HEX_FORMAT.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private Long sizeBytes(String value) {
        return value == null ? null : (long) bytes(value).length;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String summarySuffix(String argumentSummary) {
        return argumentSummary.isBlank() ? "" : " with " + argumentSummary;
    }

    private String argumentSummary(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "";
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolInput);
            if (root == null || root.isNull() || root.isMissingNode() || root.isEmpty()) {
                return "";
            }
            if (!root.isObject()) {
                return "arguments=" + nodeSummary("arguments", root);
            }

            List<String> fields = root.properties().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .limit(MAX_SUMMARY_FIELDS)
                    .map(entry -> entry.getKey() + "=" + nodeSummary(entry.getKey(), entry.getValue()))
                    .toList();
            if (fields.isEmpty()) {
                return "";
            }
            String summary = "arguments " + String.join(", ", fields);
            if (root.size() > MAX_SUMMARY_FIELDS) {
                summary += ", additionalArguments=" + (root.size() - MAX_SUMMARY_FIELDS);
            }
            return summary;
        } catch (Exception ex) {
            return "arguments=unreadable";
        }
    }

    private String nodeSummary(String fieldName, JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.booleanValue());
        }
        if (value.isNumber()) {
            return value.numberValue().toString();
        }
        if (value.isTextual()) {
            return stringSummary(fieldName, value.asString());
        }
        if (value.isArray()) {
            return "array[" + value.size() + "]";
        }
        if (value.isObject()) {
            return "object[" + value.size() + "]";
        }
        return value.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    private String stringSummary(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            return "empty";
        }

        String normalizedField = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        String trimmed = value.trim();
        if (normalizedField.contains("ticker") || normalizedField.contains("symbol")) {
            return safeTicker(trimmed);
        }
        if (normalizedField.contains("date") && trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return trimmed;
        }
        return "present";
    }

    private String safeTicker(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9.]", "").toUpperCase(Locale.ROOT);
        if (sanitized.isBlank()) {
            return "present";
        }
        return sanitized.length() <= 12 ? sanitized : "present";
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String rootCauseType(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

}
