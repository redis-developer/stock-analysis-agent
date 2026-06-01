package com.redis.stockanalysisagent.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestReport(
        String symbol,
        String strategy,
        String interval,
        String adjustment,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal initialCash,
        BigDecimal finalValue,
        BigDecimal totalReturnPercent,
        BigDecimal buyAndHoldReturnPercent,
        BigDecimal maxDrawdownPercent,
        int candleCount,
        int tradeCount,
        String assumptions,
        List<BacktestTrade> trades
) {
}
