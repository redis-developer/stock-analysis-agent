package com.redis.stockanalysisagent.agent.coordinator;

import com.redis.stockanalysisagent.agent.AgentType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CoordinatorResponse {

    public enum FinishReason {
        NEEDS_MORE_INPUT,
        DIRECT_RESPONSE,
        COMPLETED,
        CANNOT_PROCEED,
        OUT_OF_SCOPE
    }

    private FinishReason finishReason;
    private String nextPrompt;
    private String finalResponse;
    private String conversationId;
    private String resolvedTicker;
    private List<String> resolvedTickers = new ArrayList<>();
    private String resolvedQuestion;
    private List<AgentType> selectedAgents = new ArrayList<>();
    private String reasoning;

    public CoordinatorResponse() {
    }

    public CoordinatorResponse(
            FinishReason finishReason,
            String nextPrompt,
            String finalResponse,
            String resolvedTicker,
            String resolvedQuestion,
            List<AgentType> selectedAgents,
            String reasoning
    ) {
        this.finishReason = finishReason;
        this.nextPrompt = nextPrompt;
        this.finalResponse = finalResponse;
        setResolvedTicker(resolvedTicker);
        this.resolvedQuestion = resolvedQuestion;
        this.selectedAgents = selectedAgents == null ? new ArrayList<>() : selectedAgents;
        this.reasoning = reasoning;
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(FinishReason finishReason) {
        this.finishReason = finishReason;
    }

    public String getNextPrompt() {
        return nextPrompt;
    }

    public void setNextPrompt(String nextPrompt) {
        this.nextPrompt = nextPrompt;
    }

    public String getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(String finalResponse) {
        this.finalResponse = finalResponse;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getResolvedTicker() {
        return resolvedTicker;
    }

    public void setResolvedTicker(String resolvedTicker) {
        String normalizedTicker = normalizeTicker(resolvedTicker);
        this.resolvedTicker = normalizedTicker;
        if (normalizedTicker != null) {
            this.resolvedTickers = new ArrayList<>(List.of(normalizedTicker));
        }
    }

    public List<String> getResolvedTickers() {
        return resolvedTickers;
    }

    public void setResolvedTickers(List<String> resolvedTickers) {
        this.resolvedTickers = normalizeTickers(resolvedTickers);
        this.resolvedTicker = this.resolvedTickers.isEmpty() ? null : this.resolvedTickers.getFirst();
    }

    public String getResolvedQuestion() {
        return resolvedQuestion;
    }

    public void setResolvedQuestion(String resolvedQuestion) {
        this.resolvedQuestion = resolvedQuestion;
    }

    public List<AgentType> getSelectedAgents() {
        return selectedAgents;
    }

    public void setSelectedAgents(List<AgentType> selectedAgents) {
        this.selectedAgents = selectedAgents == null ? new ArrayList<>() : selectedAgents;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    private List<String> normalizeTickers(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeTicker)
                .filter(Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private String normalizeTicker(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }
}
