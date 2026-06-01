package com.redis.stockanalysisagent.backtest;

import com.redis.stockanalysisagent.cache.CacheNames;
import com.redis.stockanalysisagent.cache.ExternalApiUsageService;
import com.redis.stockanalysisagent.providers.HistoricalCandleProvider;
import com.redis.stockanalysisagent.stock.HistoricalCandle;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class HistoricalCandleService {

    private final HistoricalCandleProvider historicalCandleProvider;
    private final HistoricalCandleRepository historicalCandleRepository;
    private final HistoricalCandleProperties properties;
    private final ExternalApiUsageService apiUsageService;

    public HistoricalCandleService(
            HistoricalCandleProvider historicalCandleProvider,
            HistoricalCandleRepository historicalCandleRepository,
            HistoricalCandleProperties properties,
            ExternalApiUsageService apiUsageService
    ) {
        this.historicalCandleProvider = historicalCandleProvider;
        this.historicalCandleRepository = historicalCandleRepository;
        this.properties = properties;
        this.apiUsageService = apiUsageService;
    }

    public List<HistoricalCandle> getCandles(String ticker, LocalDate startDate, LocalDate endDate) {
        return getCandles(ticker, properties.getInterval(), properties.getAdjustment(), startDate, endDate);
    }

    public List<HistoricalCandle> getCandles(
            String ticker,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    ) {
        String symbol = normalizeSymbol(ticker);
        String normalizedInterval = normalizeRequired(interval, "interval");
        String normalizedAdjustment = normalizeRequired(adjustment, "adjustment").toLowerCase(Locale.ROOT);
        validateDateRange(startDate, endDate);

        Set<LocalDate> fetchedDates = historicalCandleRepository.fetchedDates(
                symbol,
                normalizedInterval,
                normalizedAdjustment,
                startDate,
                endDate
        );

        for (DateRange missingRange : missingRanges(startDate, endDate, fetchedDates)) {
            apiUsageService.recordHitForCacheName(CacheNames.HISTORICAL_CANDLES);
            List<HistoricalCandle> candles = historicalCandleProvider.fetchCandles(
                    symbol,
                    normalizedInterval,
                    normalizedAdjustment,
                    missingRange.startDate(),
                    missingRange.endDate()
            );
            historicalCandleRepository.saveCandles(candles);
            historicalCandleRepository.saveFetchedRange(
                    symbol,
                    normalizedInterval,
                    normalizedAdjustment,
                    missingRange.startDate(),
                    missingRange.endDate()
            );
        }

        return historicalCandleRepository.findCandles(
                symbol,
                normalizedInterval,
                normalizedAdjustment,
                startDate,
                endDate
        );
    }

    private List<DateRange> missingRanges(LocalDate startDate, LocalDate endDate, Set<LocalDate> fetchedDates) {
        List<DateRange> ranges = new ArrayList<>();
        LocalDate rangeStart = null;
        LocalDate previousMissing = null;

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (fetchedDates.contains(date)) {
                if (rangeStart != null) {
                    ranges.add(new DateRange(rangeStart, previousMissing));
                    rangeStart = null;
                    previousMissing = null;
                }
                continue;
            }

            if (rangeStart == null) {
                rangeStart = date;
            }
            previousMissing = date;
        }

        if (rangeStart != null) {
            ranges.add(new DateRange(rangeStart, previousMissing));
        }

        return ranges;
    }

    private String normalizeSymbol(String ticker) {
        return normalizeRequired(ticker, "ticker").toUpperCase(Locale.ROOT);
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null) {
            throw new IllegalArgumentException("startDate is required.");
        }

        if (endDate == null) {
            throw new IllegalArgumentException("endDate is required.");
        }

        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate.");
        }
    }

    private record DateRange(
            LocalDate startDate,
            LocalDate endDate
    ) {
    }
}
