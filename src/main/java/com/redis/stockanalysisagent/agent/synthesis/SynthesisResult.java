package com.redis.stockanalysisagent.agent.synthesis;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;

public class SynthesisResult {

    private String message;
    private String finalResponse;
    private TokenUsageSummary tokenUsage;

    public SynthesisResult() {
    }

    public SynthesisResult(String message, String finalResponse) {
        this.message = message;
        this.finalResponse = finalResponse;
    }

    public static SynthesisResult completed(String message, String finalResponse) {
        return new SynthesisResult(message, finalResponse);
    }

    public static SynthesisResult error(String message) {
        return new SynthesisResult(message, null);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(String finalResponse) {
        this.finalResponse = finalResponse;
    }

    public TokenUsageSummary getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsageSummary tokenUsage) {
        this.tokenUsage = tokenUsage;
    }
}
