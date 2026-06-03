package com.redis.stockanalysisagent.agent.backtest;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.backtest.BacktestReport;

public class BacktestResult {

    private String message;
    private BacktestReport finalResponse;
    private TokenUsageSummary tokenUsage;

    public BacktestResult() {
    }

    public BacktestResult(
            String message,
            BacktestReport finalResponse
    ) {
        this.message = message;
        this.finalResponse = finalResponse;
    }

    public static BacktestResult completed(String message, BacktestReport finalResponse) {
        return new BacktestResult(message, finalResponse);
    }

    public static BacktestResult error(String message) {
        return new BacktestResult(message, null);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public BacktestReport getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(BacktestReport finalResponse) {
        this.finalResponse = finalResponse;
    }

    public TokenUsageSummary getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsageSummary tokenUsage) {
        this.tokenUsage = tokenUsage;
    }
}
