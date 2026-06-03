package com.redis.stockanalysisagent.agent.marketdata;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.stock.MarketSnapshot;

public class MarketDataResult {

    private String message;
    private MarketSnapshot finalResponse;
    private TokenUsageSummary tokenUsage;

    public MarketDataResult() {
    }

    public MarketDataResult(String message, MarketSnapshot finalResponse) {
        this.message = message;
        this.finalResponse = finalResponse;
    }

    public static MarketDataResult completed(MarketSnapshot finalResponse) {
        return completed(null, finalResponse);
    }

    public static MarketDataResult completed(String message, MarketSnapshot finalResponse) {
        return new MarketDataResult(message, finalResponse);
    }

    public static MarketDataResult error(String message) {
        return new MarketDataResult(message, null);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public MarketSnapshot getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(MarketSnapshot finalResponse) {
        this.finalResponse = finalResponse;
    }

    public TokenUsageSummary getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsageSummary tokenUsage) {
        this.tokenUsage = tokenUsage;
    }
}
