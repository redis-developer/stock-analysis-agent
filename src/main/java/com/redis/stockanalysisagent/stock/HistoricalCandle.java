package com.redis.stockanalysisagent.stock;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HistoricalCandle(
        String symbol,
        String interval,
        String adjustment,
        LocalDate date,
        long timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        String source
) {
}
