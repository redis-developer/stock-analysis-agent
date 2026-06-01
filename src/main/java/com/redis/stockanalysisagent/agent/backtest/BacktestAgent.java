package com.redis.stockanalysisagent.agent.backtest;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class BacktestAgent {

    private final ChatClient backtestChatClient;
    private final BacktestTools backtestTools;

    public BacktestAgent(
            @Qualifier("backtestChatClient") ChatClient backtestChatClient,
            BacktestTools backtestTools
    ) {
        this.backtestChatClient = backtestChatClient;
        this.backtestTools = backtestTools;
    }

    public BacktestResult execute(String ticker, String question) {
        var parsedRequest = BacktestRequestParser.parse(ticker, question);
        if (parsedRequest.isPresent()) {
            var request = parsedRequest.get();
            var report = backtestTools.runSmaCrossoverBacktest(
                    request.symbol(),
                    request.startDate().toString(),
                    request.endDate().toString(),
                    request.shortWindow(),
                    request.longWindow(),
                    request.initialCash()
            );
            return BacktestResult.completed(summary(report), report);
        }

        ResponseEntity<ChatResponse, BacktestResult> response = backtestChatClient
                .prompt()
                .user(buildPrompt(ticker, question))
                .call()
                .responseEntity(BacktestResult.class);
        TokenUsageSummary tokenUsage = TokenUsageSummary.from(response.response());

        BacktestResult entity = response.entity();
        if (entity == null) {
            throw new IllegalStateException("Backtest Agent returned no response.");
        }
        if (entity.getFinishReason() != BacktestResult.FinishReason.COMPLETED || entity.getFinalResponse() == null) {
            throw new IllegalStateException(entity.getMessage() == null || entity.getMessage().isBlank()
                    ? "Backtest Agent returned an invalid response."
                    : entity.getMessage());
        }

        entity.setTokenUsage(tokenUsage);
        return entity;
    }

    private String summary(com.redis.stockanalysisagent.backtest.BacktestReport report) {
        return "%s on %s returned %s%% versus %s%% buy and hold, with %s%% max drawdown and %d trades. Assumptions: %s".formatted(
                report.strategy(),
                report.symbol(),
                report.totalReturnPercent(),
                report.buyAndHoldReturnPercent(),
                report.maxDrawdownPercent(),
                report.tradeCount(),
                report.assumptions()
        );
    }

    private String buildPrompt(String ticker, String question) {
        return """
                TICKER
                %s

                USER_QUESTION
                %s

                INSTRUCTIONS
                Use the backtest tool to run the requested technical signal backtest.
                Use runSmaCrossoverBacktest for moving average crossover requests.
                If the user omits SMA windows, use 20 and 50.
                If the user omits starting cash, use 10000.
                Use only adjusted daily historical candles returned by the tool.
                Do not use current market snapshots, current technical snapshots, fundamentals, or news.
                finalResponse must be the exact tool result.
                message should summarize return, benchmark return, drawdown, trade count, and assumptions in plain prose.
                Return valid JSON matching the requested schema.
                """.formatted(ticker.toUpperCase(), question);
    }
}
