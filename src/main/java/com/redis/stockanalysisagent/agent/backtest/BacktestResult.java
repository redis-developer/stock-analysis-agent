package com.redis.stockanalysisagent.agent.backtest;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.backtest.BacktestReport;

public class BacktestResult {

    public enum FinishReason {
        COMPLETED,
        NEEDS_MORE_INPUT,
        ERROR
    }

    private FinishReason finishReason;
    private String message;
    private BacktestReport finalResponse;
    private TokenUsageSummary tokenUsage;

    public BacktestResult() {
    }

    public BacktestResult(
            FinishReason finishReason,
            String message,
            BacktestReport finalResponse
    ) {
        this.finishReason = finishReason;
        this.message = message;
        this.finalResponse = finalResponse;
    }

    public static BacktestResult completed(String message, BacktestReport finalResponse) {
        return new BacktestResult(FinishReason.COMPLETED, message, finalResponse);
    }

    public static BacktestResult error(String message) {
        return new BacktestResult(FinishReason.ERROR, message, null);
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(FinishReason finishReason) {
        this.finishReason = finishReason;
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
