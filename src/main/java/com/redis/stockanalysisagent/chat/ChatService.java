package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.memory.AmsChatMemoryRepository;
import com.redis.stockanalysisagent.session.ConversationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String KIND_SYSTEM = "system";

    private final ChatMemory chatMemory;
    private final AmsChatMemoryRepository memoryRepository;
    private final ChatAnalysisService chatAnalysisService;
    private final ExternalDataCache externalDataCache;
    private final ChatProgressPublisher progressPublisher;

    public ChatService(
            ChatMemory chatMemory,
            AmsChatMemoryRepository memoryRepository,
            ChatAnalysisService chatAnalysisService,
            ExternalDataCache externalDataCache,
            ChatProgressPublisher progressPublisher
    ) {
        this.chatMemory = chatMemory;
        this.memoryRepository = memoryRepository;
        this.chatAnalysisService = chatAnalysisService;
        this.externalDataCache = externalDataCache;
        this.progressPublisher = progressPublisher;
    }

    public ChatTurn chat(
            String userId,
            String sessionId,
            String message,
            Integer retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled
    ) {
        String conversationId = ConversationId.of(userId, sessionId).value();
        String normalizedMessage = message == null ? "" : message.trim();
        List<ChatExecutionStep> executionSteps = new ArrayList<>();
        memoryRepository.setLastRetrievedMemories(List.of());
        memoryRepository.setLastMemoryRetrievalDurationMs(null);
        long analysisStartedAt = System.nanoTime();
        progressPublisher.running(
                "REQUEST_ANALYSIS",
                "Analyzing request",
                ChatProgressPublisher.KIND_SYSTEM,
                "Preparing the stock analysis request."
        );

        ChatAnalysisService.AnalysisTurn analysisTurn;
        externalDataCache.setCachingEnabled(apiCachingEnabled);
        try {
            analysisTurn = chatAnalysisService.analyze(
                    normalizedMessage,
                    conversationId,
                    retrievedMemoriesLimit,
                    semanticCachingEnabled,
                    semanticCacheKey(userId, normalizedMessage)
            );
        } catch (RuntimeException ex) {
            progressPublisher.failed(
                    "REQUEST_ANALYSIS",
                    "Analyzing request",
                    ChatProgressPublisher.KIND_SYSTEM,
                    elapsedDurationMs(analysisStartedAt),
                    errorMessage(ex)
            );
            throw ex;
        } finally {
            externalDataCache.clearCachingEnabled();
        }
        executionSteps.addAll(analysisTurn.executionSteps());
        progressPublisher.completed(
                "REQUEST_ANALYSIS",
                "Analyzing request",
                ChatProgressPublisher.KIND_SYSTEM,
                sumDurationMs(executionSteps),
                "Completed the stock analysis request."
        );

        long saveTurnStartedAt = System.nanoTime();
        progressPublisher.running(
                "TURN_SAVE",
                "Saving turn",
                ChatProgressPublisher.KIND_SYSTEM,
                "Saving the user message and assistant response."
        );
        boolean saveSucceeded = saveTurn(conversationId, normalizedMessage, analysisTurn.response());
        ChatExecutionStep saveStep = systemStep(
                "TURN_SAVE",
                "Turn save",
                elapsedDurationMs(saveTurnStartedAt),
                turnSaveSummary(saveSucceeded),
                null
        );
        executionSteps.add(saveStep);
        progressPublisher.completed(
                saveStep.id(),
                "Saving turn",
                ChatProgressPublisher.KIND_SYSTEM,
                saveStep.durationMs(),
                saveStep.summary()
        );

        return new ChatTurn(
                conversationId,
                analysisTurn.response(),
                memoryRepository.getLastRetrievedMemories(),
                analysisTurn.fromSemanticCache(),
                analysisTurn.fromSemanticGuardrail(),
                analysisTurn.tokenUsage(),
                List.copyOf(executionSteps)
        );
    }

    private boolean saveTurn(String conversationId, String message, String response) {
        try {
            chatMemory.add(conversationId, new UserMessage(message));
            chatMemory.add(conversationId, new AssistantMessage(response));
            return true;
        } catch (RuntimeException ex) {
            log.warn("Skipping working-memory save because chat persistence failed.", ex);
            return false;
        }
    }

    private ChatExecutionStep systemStep(
            String id,
            String label,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage
    ) {
        return new ChatExecutionStep(id, label, KIND_SYSTEM, durationMs, summary, tokenUsage);
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private long sumDurationMs(List<ChatExecutionStep> executionSteps) {
        return executionSteps.stream()
                .mapToLong(ChatExecutionStep::durationMs)
                .sum();
    }

    private String turnSaveSummary(boolean saveSucceeded) {
        return saveSucceeded
                ? "Persisted the user message and assistant response to working memory."
                : "Skipped working-memory persistence because the save failed.";
    }

    private String semanticCacheKey(String userId, String message) {
        return "user=%s\nmessage=%s".formatted(userId, message);
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    public record ChatTurn(
            String conversationId,
            String response,
            List<String> retrievedMemories,
            boolean fromSemanticCache,
            boolean fromSemanticGuardrail,
            TokenUsageSummary tokenUsage,
            List<ChatExecutionStep> executionSteps
    ) {
    }
}
