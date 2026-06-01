package com.redis.stockanalysisagent.backtest;

import com.redis.stockanalysisagent.stock.HistoricalCandle;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface HistoricalCandleRepository {

    Set<LocalDate> fetchedDates(
            String symbol,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    );

    void saveFetchedRange(
            String symbol,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    );

    void saveCandles(List<HistoricalCandle> candles);

    List<HistoricalCandle> findCandles(
            String symbol,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    );
}
