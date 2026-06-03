package com.redis.stockanalysisagent.agent.fundamentals;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.stock.FundamentalsSnapshot;

public class FundamentalsResult {

    private String message;
    private FundamentalsSnapshot finalResponse;
    private TokenUsageSummary tokenUsage;

    public FundamentalsResult() {
    }

    public FundamentalsResult(String message, FundamentalsSnapshot finalResponse) {
        this.message = message;
        this.finalResponse = finalResponse;
    }

    public static FundamentalsResult completed(FundamentalsSnapshot finalResponse) {
        return completed(null, finalResponse);
    }

    public static FundamentalsResult completed(String message, FundamentalsSnapshot finalResponse) {
        return new FundamentalsResult(message, finalResponse);
    }

    public static FundamentalsResult error(String message) {
        return new FundamentalsResult(message, null);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public FundamentalsSnapshot getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(FundamentalsSnapshot finalResponse) {
        this.finalResponse = finalResponse;
    }

    public TokenUsageSummary getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsageSummary tokenUsage) {
        this.tokenUsage = tokenUsage;
    }
}
