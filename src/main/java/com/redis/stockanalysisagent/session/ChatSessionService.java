package com.redis.stockanalysisagent.session;

import com.redis.agentmemory.models.workingmemory.MemoryMessage;
import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataAccess;
import com.redis.stockanalysisagent.chat.ChatExecutionStep;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository.StoredMemoryMessage;
import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;
import com.redis.stockanalysisagent.session.dto.ChatSessionMessage;
import com.redis.stockanalysisagent.session.dto.ChatSessionSummary;
import com.redis.stockanalysisagent.session.dto.ChatSessionWorkflowStep;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ChatSessionService {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final int LIVE_WORKFLOW_EVENT_LIMIT = 200;

    private final ChatMemory chatMemory;
    private final AmsChatMemoryRepository memoryRepository;
    private final WorkflowService workflowService;

    public ChatSessionService(
            ChatMemory chatMemory,
            AmsChatMemoryRepository memoryRepository
    ) {
        this(chatMemory, memoryRepository, (WorkflowService) null);
    }

    @Autowired
    public ChatSessionService(
            ChatMemory chatMemory,
            AmsChatMemoryRepository memoryRepository,
            ObjectProvider<WorkflowService> workflowService
    ) {
        this(chatMemory, memoryRepository, workflowService.getIfAvailable());
    }

    ChatSessionService(
            ChatMemory chatMemory,
            AmsChatMemoryRepository memoryRepository,
            WorkflowService workflowService
    ) {
        this.chatMemory = chatMemory;
        this.memoryRepository = memoryRepository;
        this.workflowService = workflowService;
    }

    public void clearSession(String userId, String sessionId) {
        String conversationId = ConversationId.of(userId, sessionId).value();
        try {
            chatMemory.clear(conversationId);
        } catch (RuntimeException ignored) {
        }
    }

    public List<String> listSessions(String userId) {
        return memoryRepository.findConversationIds(userId).stream()
                .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    public List<ChatSessionSummary> summarizeSessions(String userId, List<String> sessionIds) {
        if (sessionIds == null) {
            return List.of();
        }

        return sessionIds.stream()
                .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                .distinct()
                .map(sessionId -> summarizeSession(userId, sessionId))
                .toList();
    }

    public List<ChatSessionMessage> getSessionMessages(String userId, String sessionId) {
        String conversationId = conversationId(userId, sessionId);
        List<StoredMemoryMessage> storedMessages = storedMessages(conversationId);
        if (storedMessages != null && !storedMessages.isEmpty()) {
            return storedMessages.stream()
                    .map(this::toSessionMessage)
                    .filter(message -> message != null && !message.content().isBlank())
                    .toList();
        }

        return memoryRepository.findMemoryMessagesByConversationId(conversationId).stream()
                .map(this::toSessionMessage)
                .filter(message -> message != null && !message.content().isBlank())
                .toList();
    }

    public ChatSessionMetadata sessionMetadata(String userId, String sessionId) {
        return sessionMetadata(storedMessages(conversationId(userId, sessionId)), latestWorkflowState(sessionId));
    }

    private ChatSessionSummary summarizeSession(String userId, String sessionId) {
        String conversationId = conversationId(userId, sessionId);
        List<StoredMemoryMessage> storedMessages = storedMessages(conversationId);
        String createdAt = storedMessages.stream()
                .map(StoredMemoryMessage::timestamp)
                .filter(timestamp -> timestamp != null && !timestamp.isBlank())
                .findFirst()
                .orElseGet(() -> sessionCreatedAt(userId, sessionId));

        return new ChatSessionSummary(sessionId, createdAt, sessionMetadata(storedMessages, latestWorkflowState(sessionId)));
    }

    private String conversationId(String userId, String sessionId) {
        return ConversationId.of(userId, sessionId).value();
    }

    private List<StoredMemoryMessage> storedMessages(String conversationId) {
        List<StoredMemoryMessage> storedMessages = memoryRepository.findStoredMessagesByConversationId(conversationId);
        return storedMessages == null ? List.of() : storedMessages;
    }

    private ChatSessionMessage toSessionMessage(StoredMemoryMessage message) {
        if (message == null || message.content() == null) {
            return null;
        }

        String role = switch (message.role()) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            default -> null;
        };

        if ("assistant".equals(role) && isInternalCoordinatorPayload(message.content())) {
            return null;
        }

        return role != null
                ? new ChatSessionMessage(
                        role,
                        message.content(),
                        message.timestamp(),
                        tokenUsage(message.metadata() == null ? null : message.metadata().get("tokenUsage")),
                        executionSteps(message.metadata()),
                        booleanFlag(message.metadata(), "fromSemanticCache"),
                        booleanFlag(message.metadata(), "fromSemanticGuardrail")
                )
                : null;
    }

    private ChatSessionMessage toSessionMessage(MemoryMessage message) {
        if (message == null || message.getContent() == null) {
            return null;
        }

        String messageRole = message.getRole();
        if (messageRole == null) {
            return null;
        }

        String role = switch (messageRole) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            default -> null;
        };

        if ("assistant".equals(role) && isInternalCoordinatorPayload(message.getContent())) {
            return null;
        }

        String timestamp = message.getCreatedAt() != null ? message.getCreatedAt().toString() : null;
        return role != null ? new ChatSessionMessage(role, message.getContent(), timestamp) : null;
    }

    private ChatSessionMetadata sessionMetadata(List<StoredMemoryMessage> messages, WorkflowState latestWorkflow) {
        if (messages == null || messages.isEmpty()) {
            return withWorkflowMetadata(ChatSessionMetadata.empty(), latestWorkflow);
        }

        LinkedHashSet<String> tickers = new LinkedHashSet<>();
        LinkedHashSet<String> triggeredAgents = new LinkedHashSet<>();
        for (StoredMemoryMessage message : messages) {
            if (message == null || message.metadata() == null) {
                continue;
            }
            addMetadataValues(tickers, message.metadata().get("tickers"));
            addMetadataValues(triggeredAgents, message.metadata().get("triggeredAgents"));
        }

        return withWorkflowMetadata(
                new ChatSessionMetadata(List.copyOf(tickers), List.copyOf(triggeredAgents)),
                latestWorkflow
        );
    }

    private ChatSessionMetadata withWorkflowMetadata(
            ChatSessionMetadata metadata,
            WorkflowState latestWorkflow
    ) {
        if (latestWorkflow == null) {
            return metadata;
        }

        WorkflowMetadata workflow = latestWorkflow.metadata();
        Map<String, String> fields = latestWorkflow.fields();
        return new ChatSessionMetadata(
                metadata.tickers(),
                metadata.triggeredAgents(),
                workflow.workflowId(),
                workflow.status().name(),
                workflowMode(fields),
                latestWorkflow.recoveredFromWorkflowId(),
                latestWorkflow.replayCheckpointId(),
                blankToNull(fields.get(WorkflowService.RECOVERED_BY_WORKFLOW_ID)),
                blankToNull(fields.get("failureReason")),
                latestWorkflow.steps()
        );
    }

    private WorkflowState latestWorkflowState(String sessionId) {
        if (workflowService == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }

        List<String> workflowIds = workflowService.sessionWorkflowIds(sessionId);
        for (int index = workflowIds.size() - 1; index >= 0; index--) {
            String workflowId = workflowIds.get(index);
            WorkflowMetadata workflow = workflowService.readWorkflow(workflowId);
            if (workflow != null) {
                Map<String, String> fields = workflowService.workflowFields(workflowId);
                RecoverySource recoverySource = recoverySource(workflow, fields);
                List<ChatSessionWorkflowStep> steps = workflowSteps(workflowId, recoverySource);
                return new WorkflowState(workflow, fields, recoverySource.recoveredFromWorkflowId(), recoverySource.checkpointId(), steps);
            }
        }
        return null;
    }

    private RecoverySource recoverySource(WorkflowMetadata workflow, Map<String, String> fields) {
        String replayedFromWorkflowId = blankToNull(fields.get(WorkflowService.REPLAYED_FROM_WORKFLOW_ID));
        String replayCheckpointId = blankToNull(fields.get(WorkflowService.REPLAY_CHECKPOINT_ID));
        if (replayedFromWorkflowId != null && replayCheckpointId != null) {
            return new RecoverySource(replayedFromWorkflowId, replayCheckpointId);
        }
        if (workflow.status() == WorkflowStatus.RECOVERING) {
            String latestCheckpointId = latestCheckpointId(workflow.workflowId());
            if (latestCheckpointId != null) {
                return new RecoverySource(workflow.workflowId(), latestCheckpointId);
            }
        }
        return new RecoverySource(replayedFromWorkflowId, replayCheckpointId);
    }

    private List<ChatSessionWorkflowStep> workflowSteps(String workflowId, RecoverySource recoverySource) {
        if (workflowId.equals(recoverySource.recoveredFromWorkflowId()) && recoverySource.checkpointId() != null) {
            return recoveredWorkflowSteps(workflowId, recoverySource.checkpointId());
        }

        List<ChatSessionWorkflowStep> steps = new ArrayList<>();
        steps.addAll(recoveredWorkflowSteps(
                recoverySource.recoveredFromWorkflowId(),
                recoverySource.checkpointId()
        ));
        steps.addAll(workflowService.workflowEvents(workflowId, LIVE_WORKFLOW_EVENT_LIMIT).stream()
                .map(event -> workflowStep(event, false))
                .filter(step -> step != null)
                .toList());
        return List.copyOf(steps);
    }

    private List<ChatSessionWorkflowStep> recoveredWorkflowSteps(String workflowId, String checkpointId) {
        if (workflowId == null || checkpointId == null) {
            return List.of();
        }

        List<Map<String, String>> events = workflowService.workflowEvents(workflowId, LIVE_WORKFLOW_EVENT_LIMIT);
        boolean hasCheckpointEvent = events.stream()
                .anyMatch(event -> checkpointId.equals(value(event, "checkpointId")));
        String checkpointedStepId = checkpointedStepId(checkpointId);
        List<ChatSessionWorkflowStep> steps = new ArrayList<>();
        boolean foundCheckpoint = false;
        for (Map<String, String> event : events) {
            ChatSessionWorkflowStep step = workflowStep(event, true);
            if (step != null) {
                steps.add(step);
            }
            if (checkpointId.equals(value(event, "checkpointId"))
                    || (!hasCheckpointEvent && isCompletedStep(event, checkpointedStepId))) {
                foundCheckpoint = true;
                break;
            }
        }

        return foundCheckpoint ? List.copyOf(steps) : List.of();
    }

    private String checkpointedStepId(String checkpointId) {
        String value = blankToNull(checkpointId);
        if (value == null) {
            return "";
        }
        int lastSeparator = value.lastIndexOf(':');
        if (lastSeparator < 0) {
            return value;
        }
        int actorSeparator = value.lastIndexOf(':', lastSeparator - 1);
        if (actorSeparator < 0) {
            return value;
        }
        return value.substring(0, actorSeparator);
    }

    private boolean isCompletedStep(Map<String, String> event, String stepId) {
        return stepId != null
                && !stepId.isBlank()
                && stepId.equals(value(event, "stepId"))
                && "completed".equals(value(event, "status"));
    }

    private String latestCheckpointId(String workflowId) {
        List<Map<String, String>> events = workflowService.workflowEvents(workflowId, LIVE_WORKFLOW_EVENT_LIMIT);
        for (int index = events.size() - 1; index >= 0; index--) {
            String checkpointId = blankToNull(events.get(index).get("checkpointId"));
            if (checkpointId != null) {
                return checkpointId;
            }
        }
        return null;
    }

    private ChatSessionWorkflowStep workflowStep(Map<String, String> event, boolean recovered) {
        if (event == null || event.isEmpty()) {
            return null;
        }

        String stepId = value(event, "stepId");
        if (stepId.isBlank()) {
            return null;
        }

        String toolName = value(event, "toolName");
        return new ChatSessionWorkflowStep(
                stepId,
                workflowStepLabel(stepId, toolName),
                value(event, "kind"),
                value(event, "status"),
                longObject(value(event, "durationMs")),
                value(event, "summary"),
                value(event, "actorType"),
                value(event, "actorName"),
                recovered
        );
    }

    private String workflowStepLabel(String stepId, String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            return "Tool " + toolName;
        }
        if (stepId == null || stepId.isBlank()) {
            return "Workflow step";
        }
        if (stepId.startsWith("checkpoint:")) {
            return "Checkpoint";
        }
        return stepId.replace(':', ' ').replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private String workflowMode(Map<String, String> fields) {
        String replayedFrom = blankToNull(fields.get(WorkflowService.REPLAYED_FROM_WORKFLOW_ID));
        if (replayedFrom == null) {
            return "chat";
        }

        String clientRequestId = blankToNull(fields.get("clientRequestId"));
        return clientRequestId != null && clientRequestId.startsWith("recovery:") ? "recovery" : "replay";
    }

    private String value(Map<String, String> fields, String name) {
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    private Long longObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record WorkflowState(
            WorkflowMetadata metadata,
            Map<String, String> fields,
            String recoveredFromWorkflowId,
            String replayCheckpointId,
            List<ChatSessionWorkflowStep> steps
    ) {
    }

    private record RecoverySource(
            String recoveredFromWorkflowId,
            String checkpointId
    ) {
    }

    private void addMetadataValues(LinkedHashSet<String> target, Object rawValue) {
        if (rawValue instanceof List<?> values) {
            values.forEach(value -> addMetadataValue(target, value));
            return;
        }
        addMetadataValue(target, rawValue);
    }

    private void addMetadataValue(LinkedHashSet<String> target, Object rawValue) {
        if (!(rawValue instanceof String text) || text.isBlank()) {
            return;
        }

        for (String part : text.split("[,\\s]+")) {
            String normalized = part.trim().toUpperCase();
            if (!normalized.isBlank()) {
                target.add(normalized);
            }
        }
    }

    private List<ChatExecutionStep> executionSteps(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get("executionSteps") instanceof List<?> rawSteps)) {
            return List.of();
        }

        return rawSteps.stream()
                .map(this::executionStep)
                .filter(step -> step != null)
                .toList();
    }

    private ChatExecutionStep executionStep(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }

        return new ChatExecutionStep(
                text(raw.get("id")),
                text(raw.get("label")),
                text(raw.get("kind")),
                longValue(raw.get("durationMs")),
                text(raw.get("summary")),
                tokenUsage(raw.get("tokenUsage")),
                integerValue(raw.get("loop")),
                dataAccesses(raw.get("dataAccesses")),
                text(raw.get("actorType")),
                text(raw.get("actorName"))
        );
    }

    private TokenUsageSummary tokenUsage(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }

        return new TokenUsageSummary(
                integerValue(raw.get("promptTokens")),
                integerValue(raw.get("completionTokens")),
                integerValue(raw.get("totalTokens"))
        );
    }

    private boolean booleanFlag(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private List<ExternalDataAccess> dataAccesses(Object value) {
        if (!(value instanceof List<?> rawAccesses)) {
            return List.of();
        }

        return rawAccesses.stream()
                .map(this::dataAccess)
                .filter(access -> access != null)
                .toList();
    }

    private ExternalDataAccess dataAccess(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }

        return new ExternalDataAccess(
                text(raw.get("cacheName")),
                text(raw.get("key")),
                text(raw.get("source")),
                longValue(raw.get("durationMs"))
        );
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Long.parseLong(text));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String sessionCreatedAt(String userId, String sessionId) {
        String conversationId = ConversationId.of(userId, sessionId).value();
        return memoryRepository.findMemoryMessagesByConversationId(conversationId).stream()
                .map(MemoryMessage::getCreatedAt)
                .filter(timestamp -> timestamp != null)
                .findFirst()
                .map(Object::toString)
                .orElse(null);
    }

    private boolean isInternalCoordinatorPayload(String content) {
        if (content == null) {
            return false;
        }

        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(trimmed);
            return root.get("response") != null || root.get("finalResponse") != null || root.get("nextPrompt") != null;
        } catch (Exception ignored) {
            return false;
        }
    }
}
