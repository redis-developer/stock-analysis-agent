package com.redis.stockanalysisagent.semanticguardrail;

import com.redis.stockanalysisagent.chat.WorkflowProgress;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.core.io.JsonStringEncoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SemanticGuardrailAdvisor implements CallAdvisor {

    public static final String GUARDRAIL_BLOCKED = "semantic_guardrail_blocked";
    public static final String GUARDRAIL_ROUTE = "semantic_guardrail_route";
    public static final String GUARDRAIL_DURATION_MS = "semantic_guardrail_duration_ms";
    private static final int DEFAULT_ORDER = 25;
    private static final JsonStringEncoder JSON_STRING_ENCODER = JsonStringEncoder.getInstance();

    private final SemanticGuardrailService semanticGuardrailService;
    private final WorkflowProgress workflowProgress;

    public SemanticGuardrailAdvisor(
            SemanticGuardrailService semanticGuardrailService,
            WorkflowProgress workflowProgress
    ) {
        this.semanticGuardrailService = semanticGuardrailService;
        this.workflowProgress = workflowProgress;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long startedAt = System.nanoTime();
        workflowProgress.running(
                "SEMANTIC_GUARDRAIL",
                "Semantic guardrail",
                WorkflowProgress.KIND_SYSTEM,
                "Checking whether the request is in scope."
        );
        String userMessage = userMessage(request);
        var match = semanticGuardrailService.match(userMessage);
        long durationMs = elapsedDurationMs(startedAt);
        if (match.isEmpty()) {
            workflowProgress.completed(
                    "SEMANTIC_GUARDRAIL",
                    "Semantic guardrail",
                    WorkflowProgress.KIND_SYSTEM,
                    durationMs,
                    "Allowed the request to continue."
            );
            ChatClientResponse response = chain.nextCall(request);
            return response == null ? null : response.mutate()
                    .chatResponse(withGuardrailMetadata(response.chatResponse(), false, null, durationMs))
                    .context(GUARDRAIL_BLOCKED, false)
                    .context(GUARDRAIL_DURATION_MS, durationMs)
                    .build();
        }

        String routeName = match.get().routeName();
        workflowProgress.completed(
                "SEMANTIC_GUARDRAIL",
                "Semantic guardrail",
                WorkflowProgress.KIND_SYSTEM,
                durationMs,
                "Blocked the request with the %s semantic guardrail.".formatted(formatGuardrailRoute(routeName))
        );
        return ChatClientResponse.builder()
                .chatResponse(toChatResponse(blockedResponse(routeName), routeName, durationMs))
                .context(request.context())
                .context(GUARDRAIL_BLOCKED, true)
                .context(GUARDRAIL_ROUTE, routeName)
                .context(GUARDRAIL_DURATION_MS, durationMs)
                .build();
    }

    private String userMessage(ChatClientRequest request) {
        if (request == null || request.prompt() == null || request.prompt().getUserMessage() == null) {
            return null;
        }
        return request.prompt().getUserMessage().getText();
    }

    private ChatResponse toChatResponse(String finalResponse, String routeName, long durationMs) {
        return ChatResponse.builder()
                .metadata(ChatResponseMetadata.builder()
                        .keyValue(GUARDRAIL_BLOCKED, true)
                        .keyValue(GUARDRAIL_ROUTE, routeName)
                        .keyValue(GUARDRAIL_DURATION_MS, durationMs)
                        .build())
                .generations(List.of(new Generation(new AssistantMessage(toCoordinatorPayload(finalResponse)))))
                .build();
    }

    private ChatResponse withGuardrailMetadata(
            ChatResponse chatResponse,
            boolean blocked,
            String routeName,
            long durationMs
    ) {
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

        metadataBuilder
                .keyValue(GUARDRAIL_BLOCKED, blocked)
                .keyValue(GUARDRAIL_DURATION_MS, durationMs);
        if (routeName != null && !routeName.isBlank()) {
            metadataBuilder.keyValue(GUARDRAIL_ROUTE, routeName);
        }
        return new ChatResponse(chatResponse.getResults(), metadataBuilder.build());
    }

    private String blockedResponse(String routeName) {
        return switch (routeName) {
            case SemanticGuardrailService.ALIEN_JOKES_ROUTE ->
                    "I can help with stock analysis, but I can't help with alien jokes.";
            case SemanticGuardrailService.CORPORATE_AGILE_ROUTE ->
                    "I can help with stock analysis, but I can't help with corporate agile questions.";
            default -> "I can help with stock analysis, but I can't help with that request.";
        };
    }

    private String formatGuardrailRoute(String routeName) {
        return routeName == null || routeName.isBlank() ? "blocked_route" : routeName;
    }

    private String toCoordinatorPayload(String finalResponse) {
        StringBuilder escapedFinalResponse = new StringBuilder();
        JSON_STRING_ENCODER.quoteAsString(finalResponse == null ? "" : finalResponse, escapedFinalResponse);
        return "{\"response\":\"%s\"}".formatted(escapedFinalResponse);
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }
}
