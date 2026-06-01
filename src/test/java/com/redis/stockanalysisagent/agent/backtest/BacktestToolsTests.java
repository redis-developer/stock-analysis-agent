package com.redis.stockanalysisagent.agent.backtest;

import com.redis.stockanalysisagent.backtest.BacktestReport;
import com.redis.stockanalysisagent.backtest.HistoricalCandleService;
import com.redis.stockanalysisagent.stock.HistoricalCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BacktestToolsTests {

    private final HistoricalCandleService historicalCandleService = mock(HistoricalCandleService.class);
    private final BacktestTools tools = new BacktestTools(historicalCandleService);

    @Test
    void runsSmaCrossoverBacktestFromHistoricalCandles() {
        LocalDate startDate = LocalDate.parse("2025-01-01");
        LocalDate endDate = LocalDate.parse("2025-01-08");
        when(historicalCandleService.getCandles("AAPL", startDate, endDate))
                .thenReturn(candles("AAPL", startDate, List.of(10, 10, 10, 10, 12, 14, 16, 18)));

        BacktestReport report = tools.runSmaCrossoverBacktest(
                "aapl",
                "2025-01-01",
                "2025-01-08",
                2,
                4,
                BigDecimal.valueOf(1000)
        );

        verify(historicalCandleService).getCandles("AAPL", startDate, endDate);
        assertThat(report.symbol()).isEqualTo("AAPL");
        assertThat(report.strategy()).isEqualTo("SMA crossover 2/4");
        assertThat(report.initialCash()).isEqualByComparingTo("1000.00");
        assertThat(report.tradeCount()).isEqualTo(1);
        assertThat(report.trades())
                .singleElement()
                .satisfies(trade -> {
                    assertThat(trade.action()).isEqualTo("BUY");
                    assertThat(trade.date()).isEqualTo(LocalDate.parse("2025-01-06"));
                });
    }

    private List<HistoricalCandle> candles(String symbol, LocalDate startDate, List<Integer> closes) {
        List<HistoricalCandle> candles = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            LocalDate date = startDate.plusDays(i);
            BigDecimal close = BigDecimal.valueOf(closes.get(i));
            candles.add(new HistoricalCandle(
                    symbol,
                    "1day",
                    "all",
                    date,
                    date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    close,
                    close,
                    close,
                    close,
                    1000,
                    "test"
            ));
        }
        return candles;
    }
}
