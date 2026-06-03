package com.redis.stockanalysisagent.semanticcache;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.core.io.JsonStringEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SemanticCacheSupport {

    static final String CACHE_HIT = "semantic_cache_hit";
    static final String CACHE_BYPASS = "semantic_cache_bypass";
    static final String CACHE_KEY = "semantic_cache_key";
    static final String CACHE_STORED = "semantic_cache_stored";
    static final String CACHE_DURATION_MS = "semantic_cache_duration_ms";

    private static final JsonStringEncoder JSON_STRING_ENCODER = JsonStringEncoder.getInstance();
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private SemanticCacheSupport() {
    }

    static String resolveCacheKey(ChatClientRequest request) {
        if (request == null) {
            return null;
        }

        Object contextCacheKey = request.context().get(CACHE_KEY);
        if (contextCacheKey instanceof String cacheKey && !cacheKey.isBlank()) {
            return cacheKey.trim();
        }

        if (request.prompt() == null || request.prompt().getUserMessage() == null) {
            return null;
        }

        String text = request.prompt().getUserMessage().getText();
        if (text == null) {
            return null;
        }

        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static boolean shouldBypass(ChatClientRequest request) {
        return request != null && Boolean.TRUE.equals(request.context().get(CACHE_BYPASS));
    }

    static ChatClientResponse asCacheHitResponse(
            String cacheKey,
            String finalResponse,
            Map<String, ?> context,
            long durationMs
    ) {
        return ChatClientResponse.builder()
                .chatResponse(toCoordinatorChatResponse(finalResponse, durationMs))
                .context(context)
                .context(CACHE_KEY, cacheKey)
                .context(CACHE_HIT, true)
                .context(CACHE_DURATION_MS, durationMs)
                .build();
    }

    static ChatClientResponse markMiss(ChatClientResponse response, String cacheKey, long durationMs) {
        if (response == null) {
            return null;
        }

        return response.mutate()
                .chatResponse(withMetadata(response.chatResponse(), Map.of(
                        CACHE_HIT, false,
                        CACHE_DURATION_MS, durationMs
                )))
                .context(CACHE_HIT, false)
                .context(CACHE_KEY, cacheKey)
                .context(CACHE_DURATION_MS, durationMs)
                .build();
    }

    static String extractFinalResponse(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return null;
        }

        String content = response.chatResponse().getResult().getOutput().getText();
        if (content == null) {
            return null;
        }

        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (!trimmed.startsWith("{")) {
            return trimmed;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(trimmed);
            JsonNode finalAnswer = root.get("finalAnswer");
            if (finalAnswer != null && finalAnswer.isTextual()) {
                String value = finalAnswer.asText().trim();
                return value.isEmpty() ? null : value;
            }
            JsonNode finalResponse = root.get("finalResponse");
            if (finalResponse != null && finalResponse.isTextual()) {
                String value = finalResponse.asText().trim();
                return value.isEmpty() ? null : value;
            }
            JsonNode responseNode = root.get("response");
            if (responseNode != null && responseNode.isTextual()) {
                String value = responseNode.asText().trim();
                return value.isEmpty() ? null : value;
            }
        } catch (Exception ignored) {
        }

        return trimmed;
    }

    static String toCoordinatorPayload(String finalResponse) {
        StringBuilder escapedFinalResponse = new StringBuilder();
        JSON_STRING_ENCODER.quoteAsString(finalResponse == null ? "" : finalResponse, escapedFinalResponse);
        return "{\"response\":\"%s\"}".formatted(escapedFinalResponse);
    }

    private static ChatResponse toCoordinatorChatResponse(String finalResponse, long durationMs) {
        return ChatResponse.builder()
                .metadata(ChatResponseMetadata.builder()
                        .keyValue(CACHE_HIT, true)
                        .keyValue(CACHE_DURATION_MS, durationMs)
                        .build())
                .generations(List.of(new Generation(new AssistantMessage(toCoordinatorPayload(finalResponse)))))
                .build();
    }

    static ChatResponse withMetadata(ChatResponse chatResponse, Map<String, Object> values) {
        if (chatResponse == null) {
            return null;
        }

        ChatResponseMetadata existingMetadata = chatResponse.getMetadata();
        ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder();
        if (existingMetadata != null) {
            Map<String, Object> metadataValues = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : existingMetadata.entrySet()) {
                metadataValues.put(entry.getKey(), entry.getValue());
            }
            metadataBuilder
                    .metadata(metadataValues)
                    .id(existingMetadata.getId())
                    .model(existingMetadata.getModel())
                    .rateLimit(existingMetadata.getRateLimit())
                    .usage(existingMetadata.getUsage())
                    .promptMetadata(existingMetadata.getPromptMetadata());
        }

        values.forEach(metadataBuilder::keyValue);
        return new ChatResponse(chatResponse.getResults(), metadataBuilder.build());
    }
}
