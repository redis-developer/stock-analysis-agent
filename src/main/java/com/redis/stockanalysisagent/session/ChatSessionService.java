package com.redis.stockanalysisagent.session;

import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.session.dto.ChatSessionMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;

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

    public List<ChatSessionMessage> getSessionMessages(String userId, String sessionId) {
        String conversationId = ConversationId.of(userId, sessionId).value();
        return memoryRepository.findByConversationId(conversationId).stream()
                .map(this::toSessionMessage)
                .filter(message -> message != null && !message.content().isBlank())
                .toList();
    }

    private ChatSessionMessage toSessionMessage(Message message) {
        if (message == null || message.getText() == null) {
            return null;
        }

        String role = switch (message.getMessageType()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            default -> null;
        };

        if ("assistant".equals(role) && isInternalCoordinatorPayload(message.getText())) {
            return null;
        }

        return role != null ? new ChatSessionMessage(role, message.getText()) : null;
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
            return root.get("finishReason") != null && root.get("finalResponse") != null;
        } catch (Exception ignored) {
            return false;
        }
    }
}
