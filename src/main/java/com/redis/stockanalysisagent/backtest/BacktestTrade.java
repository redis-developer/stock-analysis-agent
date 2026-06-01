package com.redis.stockanalysisagent.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BacktestTrade(
        LocalDate date,
        String action,
        BigDecimal price,
        BigDecimal shares,
        BigDecimal portfolioValue,
        String reason
) {
}
