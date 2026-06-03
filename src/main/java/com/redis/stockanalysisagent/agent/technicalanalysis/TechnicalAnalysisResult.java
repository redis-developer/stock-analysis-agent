package com.redis.stockanalysisagent.agent.technicalanalysis;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.stock.TechnicalAnalysisSnapshot;

public class TechnicalAnalysisResult {

    private String message;
    private TechnicalAnalysisSnapshot finalResponse;
    private TokenUsageSummary tokenUsage;

    public TechnicalAnalysisResult() {
    }

    public TechnicalAnalysisResult(
            String message,
            TechnicalAnalysisSnapshot finalResponse
    ) {
        this.message = message;
        this.finalResponse = finalResponse;
    }

    public static TechnicalAnalysisResult completed(TechnicalAnalysisSnapshot finalResponse) {
        return completed(null, finalResponse);
    }

    public static TechnicalAnalysisResult completed(String message, TechnicalAnalysisSnapshot finalResponse) {
        return new TechnicalAnalysisResult(message, finalResponse);
    }

    public static TechnicalAnalysisResult error(String message) {
        return new TechnicalAnalysisResult(message, null);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public TechnicalAnalysisSnapshot getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(TechnicalAnalysisSnapshot finalResponse) {
        this.finalResponse = finalResponse;
    }

    public TokenUsageSummary getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsageSummary tokenUsage) {
        this.tokenUsage = tokenUsage;
    }
}
