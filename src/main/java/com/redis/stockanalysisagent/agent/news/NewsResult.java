package com.redis.stockanalysisagent.agent.news;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.stock.NewsSnapshot;

public class NewsResult {

    private String message;
    private NewsSnapshot finalResponse;
    private TokenUsageSummary tokenUsage;

    public NewsResult() {
    }

    public NewsResult(String message, NewsSnapshot finalResponse) {
        this.message = message;
        this.finalResponse = finalResponse;
    }

    public static NewsResult completed(NewsSnapshot finalResponse) {
        return completed(null, finalResponse);
    }

    public static NewsResult completed(String message, NewsSnapshot finalResponse) {
        return new NewsResult(message, finalResponse);
    }

    public static NewsResult error(String message) {
        return new NewsResult(message, null);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public NewsSnapshot getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(NewsSnapshot finalResponse) {
        this.finalResponse = finalResponse;
    }

    public TokenUsageSummary getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsageSummary tokenUsage) {
        this.tokenUsage = tokenUsage;
    }
}
