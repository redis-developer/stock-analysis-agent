package com.redis.stockanalysisagent.agent.backtest;

import com.redis.stockanalysisagent.backtest.BacktestReport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BacktestAgentTests {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final BacktestTools backtestTools = mock(BacktestTools.class);
    private final BacktestAgent agent = new BacktestAgent(chatClient, backtestTools);

    @Test
    void completeSmaBacktestRequestsRunDeterministically() {
        BacktestReport report = report();
        org.mockito.Mockito.when(backtestTools.runSmaCrossoverBacktest(
                "AAPL",
                "2020-01-01",
                "2025-12-31",
                20,
                50,
                BigDecimal.valueOf(10_000)
        )).thenReturn(report);

        BacktestResult result = agent.execute(
                "AAPL",
                "Backtest AAPL from 2020-01-01 to 2025-12-31 using a 20 day and 50 day moving average crossover strategy."
        );

        assertThat(result.getFinalResponse()).isSameAs(report);
        assertThat(result.getMessage()).contains("SMA crossover 20/50 on AAPL returned 12.3%");
        verify(backtestTools).runSmaCrossoverBacktest(
                "AAPL",
                "2020-01-01",
                "2025-12-31",
                20,
                50,
                BigDecimal.valueOf(10_000)
        );
    }

    private BacktestReport report() {
        return new BacktestReport(
                "AAPL",
                "SMA crossover 20/50",
                "1day",
                "all",
                LocalDate.parse("2020-01-01"),
                LocalDate.parse("2025-12-31"),
                BigDecimal.valueOf(10_000),
                BigDecimal.valueOf(11_230),
                BigDecimal.valueOf(12.30),
                BigDecimal.valueOf(20.10),
                BigDecimal.valueOf(8.20),
                1500,
                4,
                "Adjusted daily candles.",
                List.of()
        );
    }
}
