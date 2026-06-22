package com.redis.stockanalysisagent.memory;

import com.redis.agentmemory.models.workingmemory.MemoryMessage;
import com.redis.agentmemory.models.workingmemory.WorkingMemory;
import com.redis.agentmemory.models.workingmemory.WorkingMemoryResponse;
import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataAccess;
import com.redis.stockanalysisagent.chat.ChatExecutionStep;
import com.redis.stockanalysisagent.memory.service.AgentMemoryService;
import com.redis.stockanalysisagent.memory.service.AgentMemoryApiModels.SessionEvent;
import com.redis.stockanalysisagent.session.ConversationId;
import com.redis.stockanalysisagent.workflow.ToolApproval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Spring AI ChatMemoryRepository implementation backed by Agent Memory Server.
 *
 * Conversation ids are stored as "userId:sessionId" so working memory and
 * long-term memory can both retain user and session context.
 */
public class AmsChatMemoryRepository implements ChatMemoryRepository {

    private static final int DEFAULT_TTL_SECONDS = 1800;
    private static final String MEMORY_MODEL = "gpt-4o";
    private static final Logger log = LoggerFactory.getLogger(AmsChatMemoryRepository.class);

    private final AgentMemoryService agentMemoryService;
    private final ThreadLocal<List<String>> lastRetrievedMemories = ThreadLocal.withInitial(() -> List.of());
    private final ThreadLocal<Long> lastMemoryRetrievalDurationMs = new ThreadLocal<>();

    public AmsChatMemoryRepository(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    public void setLastRetrievedMemories(List<String> memories) {
        lastRetrievedMemories.set(memories != null ? memories : List.of());
    }

    public List<String> getLastRetrievedMemories() {
        return lastRetrievedMemories.get();
    }

    public void setLastMemoryRetrievalDurationMs(Long durationMs) {
        if (durationMs == null) {
            lastMemoryRetrievalDurationMs.remove();
            return;
        }

        lastMemoryRetrievalDurationMs.set(Math.max(0, durationMs));
    }

    public Long getLastMemoryRetrievalDurationMs() {
        return lastMemoryRetrievalDurationMs.get();
    }

    @Override
    public List<String> findConversationIds() {
        return findConversationIds(null);
    }

    public List<String> findConversationIds(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        return callOrDefault(
                "list memory sessions",
                () -> agentMemoryService.listSessions(normalizedUserId).stream()
                        .map(sessionId -> ConversationId.clientSessionId(normalizedUserId, sessionId))
                        .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                        .toList(),
                List.of()
        );
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        WorkingMemoryResponse response = loadWorkingMemory(conversationId);
        if (response == null || response.getMessages() == null) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>();
        for (MemoryMessage msg : response.getMessages()) {
            Message springMessage = convertToSpringMessage(msg);
            if (springMessage != null) {
                messages.add(springMessage);
            }
        }
        return messages;
    }

    public List<MemoryMessage> findMemoryMessagesByConversationId(String conversationId) {
        WorkingMemoryResponse response = loadWorkingMemory(conversationId);
        if (response == null || response.getMessages() == null) {
            return List.of();
        }

        return response.getMessages().stream()
                .filter(message -> message != null)
                .toList();
    }

    public List<StoredMemoryMessage> findStoredMessagesByConversationId(String conversationId) {
        ConversationId parsed = ConversationId.parse(conversationId);
        return callOrDefault(
                "load session events for conversation " + conversationId,
                () -> agentMemoryService.listSessionEvents(parsed.sessionId(), parsed.userId()).stream()
                        .map(this::toStoredMemoryMessage)
                        .filter(message -> message != null)
                        .toList(),
                List.of()
        );
    }

    public void saveTurn(
            String conversationId,
            String userMessage,
            String assistantResponse,
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail
    ) {
        saveTurn(
                conversationId,
                userMessage,
                assistantResponse,
                executionSteps,
                tickers,
                triggeredAgents,
                fromSemanticCache,
                fromSemanticGuardrail,
                List.of()
        );
    }

    public void saveTurn(
            String conversationId,
            String userMessage,
            String assistantResponse,
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail,
            List<String> retrievedMemories
    ) {
        saveTurn(
                conversationId,
                userMessage,
                assistantResponse,
                executionSteps,
                tickers,
                triggeredAgents,
                fromSemanticCache,
                fromSemanticGuardrail,
                retrievedMemories,
                null
        );
    }

    public void saveTurn(
            String conversationId,
            String userMessage,
            String assistantResponse,
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail,
            List<String> retrievedMemories,
            ToolApproval pendingApproval
    ) {
        ConversationId parsed = ConversationId.parse(conversationId);
        String userId = parsed.userId();
        String sessionId = parsed.sessionId();
        List<MemoryMessage> existingMessages = existingMessages(conversationId);
        MemoryMessage user = memoryMessage("user", userMessage);
        MemoryMessage assistant = memoryMessage("assistant", assistantResponse);

        runSafely("save working memory turn for conversation " + conversationId, () -> {
            boolean firstMessage = existingMessages.isEmpty();
            if (!isDuplicate(user, existingMessages)) {
                agentMemoryService.appendMessageToWorkingMemory(
                        sessionId,
                        user,
                        userId,
                        MEMORY_MODEL,
                        Map.of()
                );
            }
            if (!isDuplicate(assistant, existingMessages)) {
                agentMemoryService.appendMessageToWorkingMemory(
                        sessionId,
                        assistant,
                        userId,
                        MEMORY_MODEL,
                        assistantMetadata(
                                executionSteps,
                                tickers,
                                triggeredAgents,
                                fromSemanticCache,
                                fromSemanticGuardrail,
                                retrievedMemories,
                                pendingApproval
                        )
                );
            }

            if (firstMessage) {
                applyTtl(sessionId, userId);
            }
        });
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        ConversationId parsed = ConversationId.parse(conversationId);
        String userId = parsed.userId();
        String sessionId = parsed.sessionId();
        List<MemoryMessage> existingMessages = existingMessages(conversationId);
        List<MemoryMessage> newMessages = toNewMessages(messages, existingMessages);

        if (newMessages.isEmpty()) {
            return;
        }

        runSafely("save working memory for conversation " + conversationId, () -> {
            boolean firstMessage = existingMessages.isEmpty();
            agentMemoryService.appendMessagesToWorkingMemory(
                    sessionId,
                    newMessages,
                    userId,
                    MEMORY_MODEL
            );

            if (firstMessage) {
                applyTtl(sessionId, userId);
            }
        });
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        ConversationId parsed = ConversationId.parse(conversationId);
        runSafely("delete working memory for conversation " + conversationId, () -> {
            agentMemoryService.deleteWorkingMemory(
                    parsed.sessionId(),
                    parsed.userId()
            );
        });
    }

    private boolean isDuplicate(MemoryMessage newMsg, List<MemoryMessage> existing) {
        return existing.stream().anyMatch(m ->
                m.getRole().equals(newMsg.getRole())
                        && m.getContent().equals(newMsg.getContent())
        );
    }

    private Message convertToSpringMessage(MemoryMessage msg) {
        if (msg == null || msg.getRole() == null) {
            return null;
        }

        String content = msg.getContent() != null ? msg.getContent() : "";

        return switch (msg.getRole()) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> null;
        };
    }

    private StoredMemoryMessage toStoredMemoryMessage(SessionEvent event) {
        if (event == null || event.role() == null) {
            return null;
        }

        return new StoredMemoryMessage(
                toStoredRole(event.role()),
                contentText(event),
                event.createdAt(),
                event.metadata() == null ? Map.of() : event.metadata()
        );
    }

    private String toStoredRole(com.redis.stockanalysisagent.memory.service.AgentMemoryApiModels.MessageRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
        };
    }

    private String contentText(SessionEvent event) {
        if (event.content() == null || event.content().isEmpty()) {
            return "";
        }
        return event.content().stream()
                .map(part -> part == null ? null : part.text())
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private MemoryMessage convertToAmsMessage(Message msg) {
        if (msg == null) {
            return null;
        }

        String role = switch (msg.getMessageType()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            default -> null;
        };

        if (role == null) {
            return null;
        }

        return MemoryMessage.builder()
                .role(role)
                .content(msg.getText())
                .build();
    }

    private MemoryMessage memoryMessage(String role, String content) {
        return MemoryMessage.builder()
                .role(role)
                .content(content == null ? "" : content)
                .build();
    }

    private Map<String, Object> assistantMetadata(
            List<ChatExecutionStep> executionSteps,
            List<String> tickers,
            List<String> triggeredAgents,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail,
            List<String> retrievedMemories,
            ToolApproval pendingApproval
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (fromSemanticCache) {
            metadata.put("fromSemanticCache", true);
        }
        if (fromSemanticGuardrail) {
            metadata.put("fromSemanticGuardrail", true);
        }
        if (executionSteps != null && !executionSteps.isEmpty()) {
            metadata.put("executionSteps", executionSteps.stream()
                    .map(this::executionStepMetadata)
                    .toList());
        }
        TokenUsageSummary tokenUsage = aggregateTokenUsage(executionSteps);
        if (tokenUsage != null) {
            metadata.put("tokenUsage", tokenUsageMetadata(tokenUsage));
        }
        List<String> normalizedTickers = normalizeMetadataValues(tickers);
        if (!normalizedTickers.isEmpty()) {
            metadata.put("tickers", normalizedTickers);
        }
        List<String> normalizedAgents = normalizeMetadataValues(triggeredAgents);
        if (!normalizedAgents.isEmpty()) {
            metadata.put("triggeredAgents", normalizedAgents);
        }
        List<String> normalizedMemories = normalizeTextValues(retrievedMemories);
        if (!normalizedMemories.isEmpty()) {
            metadata.put("retrievedMemories", normalizedMemories);
        }
        if (pendingApproval != null) {
            metadata.put("pendingApproval", approvalMetadata(pendingApproval));
        }
        return metadata;
    }

    private Map<String, Object> approvalMetadata(ToolApproval approval) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "approvalId", approval.approvalId());
        putIfPresent(metadata, "workflowId", approval.workflowId());
        putIfPresent(metadata, "activeWorkflowId", approval.activeWorkflowId());
        putIfPresent(metadata, "userId", approval.userId());
        putIfPresent(metadata, "sessionId", approval.sessionId());
        putIfPresent(metadata, "conversationId", approval.conversationId());
        putIfPresent(metadata, "toolName", approval.toolName());
        putIfPresent(metadata, "agentType", approval.agentType());
        putIfPresent(metadata, "ticker", approval.ticker());
        putIfPresent(metadata, "question", approval.question());
        putIfPresent(metadata, "arguments", approval.arguments());
        putIfPresent(metadata, "status", approval.status());
        putIfPresent(metadata, "createdAt", approval.createdAt());
        putIfPresent(metadata, "updatedAt", approval.updatedAt());
        putIfPresent(metadata, "decidedAt", approval.decidedAt());
        putIfPresent(metadata, "resumedWorkflowId", approval.resumedWorkflowId());
        return metadata;
    }

    private TokenUsageSummary aggregateTokenUsage(List<ChatExecutionStep> executionSteps) {
        if (executionSteps == null || executionSteps.isEmpty()) {
            return null;
        }

        return TokenUsageSummary.sum(executionSteps.stream()
                .map(ChatExecutionStep::tokenUsage)
                .toList());
    }

    private Map<String, Object> executionStepMetadata(ChatExecutionStep step) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "id", step.id());
        putIfPresent(metadata, "label", step.label());
        putIfPresent(metadata, "kind", step.kind());
        metadata.put("durationMs", step.durationMs());
        putIfPresent(metadata, "summary", step.summary());
        if (step.loop() != null) {
            metadata.put("loop", step.loop());
        }
        if (step.tokenUsage() != null) {
            metadata.put("tokenUsage", tokenUsageMetadata(step.tokenUsage()));
        }
        if (step.dataAccesses() != null && !step.dataAccesses().isEmpty()) {
            metadata.put("dataAccesses", step.dataAccesses().stream()
                    .map(this::dataAccessMetadata)
                    .toList());
        }
        return metadata;
    }

    private Map<String, Object> tokenUsageMetadata(TokenUsageSummary tokenUsage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "promptTokens", tokenUsage.promptTokens());
        putIfPresent(metadata, "completionTokens", tokenUsage.completionTokens());
        putIfPresent(metadata, "totalTokens", tokenUsage.totalTokens());
        return metadata;
    }

    private Map<String, Object> dataAccessMetadata(ExternalDataAccess dataAccess) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "cacheName", dataAccess.cacheName());
        putIfPresent(metadata, "key", dataAccess.key());
        putIfPresent(metadata, "source", dataAccess.source());
        metadata.put("durationMs", dataAccess.durationMs());
        return metadata;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private List<String> normalizeMetadataValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase())
                .distinct()
                .toList();
    }

    private List<String> normalizeTextValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private WorkingMemoryResponse loadWorkingMemory(String conversationId) {
        ConversationId parsed = ConversationId.parse(conversationId);
        return callOrDefault(
                "load working memory for conversation " + conversationId,
                () -> ownedWorkingMemory(parsed, agentMemoryService.getWorkingMemory(
                        parsed.sessionId(),
                        parsed.userId(),
                        null
                )),
                null
        );
    }

    private WorkingMemoryResponse ownedWorkingMemory(ConversationId conversationId, WorkingMemoryResponse response) {
        String userId = conversationId.userId();
        if (response == null || userId == null || response.getUserId() == null || userId.equals(response.getUserId())) {
            return response;
        }

        return null;
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? null : userId.trim();
    }

    private List<MemoryMessage> existingMessages(String conversationId) {
        WorkingMemoryResponse existing = loadWorkingMemory(conversationId);
        if (existing == null || existing.getMessages() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(existing.getMessages());
    }

    private List<MemoryMessage> toNewMessages(List<Message> messages, List<MemoryMessage> existingMessages) {
        List<MemoryMessage> newMessages = new ArrayList<>();
        for (Message message : messages) {
            MemoryMessage amsMessage = convertToAmsMessage(message);
            if (amsMessage != null && !isDuplicate(amsMessage, existingMessages)) {
                newMessages.add(amsMessage);
            }
        }
        return newMessages;
    }

    private void applyTtl(String sessionId, String userId) {
        WorkingMemoryResponse current = agentMemoryService.getWorkingMemory(sessionId, userId, null);
        if (current == null || current.getMessages() == null) {
            return;
        }

        WorkingMemory withTtl = WorkingMemory.builder()
                .sessionId(sessionId)
                .messages(current.getMessages())
                .memories(current.getMemories())
                .data(current.getData())
                .context(current.getContext())
                .userId(userId)
                .tokens(current.getTokens())
                .namespace(agentMemoryService.namespace())
                .ttlSeconds(DEFAULT_TTL_SECONDS)
                .lastAccessed(current.getLastAccessed())
                .build();

        agentMemoryService.putWorkingMemory(
                sessionId,
                withTtl,
                userId,
                MEMORY_MODEL
        );
    }

    private void runSafely(String action, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            log.warn("Skipping Agent Memory Server action because {} failed.", action, e);
        }
    }

    private <T> T callOrDefault(String action, Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            log.warn("Returning fallback because {} failed.", action, e);
            return fallback;
        }
    }

    public record StoredMemoryMessage(
            String role,
            String content,
            String timestamp,
            Map<String, Object> metadata
    ) {
    }
}
