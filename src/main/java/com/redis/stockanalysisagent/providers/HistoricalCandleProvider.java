package com.redis.stockanalysisagent.providers;

import com.redis.stockanalysisagent.stock.HistoricalCandle;

import java.time.LocalDate;
import java.util.List;

public interface HistoricalCandleProvider {

    List<HistoricalCandle> fetchCandles(
            String ticker,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    );
}
