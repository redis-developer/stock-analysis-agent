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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class ChatSessionService {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final ChatMemory chatMemory;
    private final AmsChatMemoryRepository memoryRepository;

    public ChatSessionService(
            ChatMemory chatMemory,
            AmsChatMemoryRepository memoryRepository
    ) {
        this.chatMemory = chatMemory;
        this.memoryRepository = memoryRepository;
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
        return sessionMetadata(storedMessages(conversationId(userId, sessionId)));
    }

    private ChatSessionSummary summarizeSession(String userId, String sessionId) {
        String conversationId = conversationId(userId, sessionId);
        List<StoredMemoryMessage> storedMessages = storedMessages(conversationId);
        String createdAt = storedMessages.stream()
                .map(StoredMemoryMessage::timestamp)
                .filter(timestamp -> timestamp != null && !timestamp.isBlank())
                .findFirst()
                .orElseGet(() -> sessionCreatedAt(userId, sessionId));

        return new ChatSessionSummary(sessionId, createdAt, sessionMetadata(storedMessages));
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
                        executionSteps(message.metadata())
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

    private ChatSessionMetadata sessionMetadata(List<StoredMemoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return ChatSessionMetadata.empty();
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

        return new ChatSessionMetadata(List.copyOf(tickers), List.copyOf(triggeredAgents));
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
