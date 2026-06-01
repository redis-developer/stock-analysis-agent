package com.redis.stockanalysisagent.backtest;

import com.redis.stockanalysisagent.session.ChatSessionAccess;
import com.redis.stockanalysisagent.stock.HistoricalCandle;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/market-data/candles")
public class HistoricalCandleController {

    private final HistoricalCandleService historicalCandleService;
    private final HistoricalCandleProperties properties;
    private final ChatSessionAccess sessionAccess;

    public HistoricalCandleController(
            HistoricalCandleService historicalCandleService,
            HistoricalCandleProperties properties,
            ChatSessionAccess sessionAccess
    ) {
        this.historicalCandleService = historicalCandleService;
        this.properties = properties;
        this.sessionAccess = sessionAccess;
    }

    @GetMapping
    public ResponseEntity<HistoricalCandlesResponse> candles(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String interval,
            HttpServletRequest request
    ) {
        if (sessionAccess.sessionManagementEnabled()) {
            sessionAccess.requireSessionUserId(request.getSession(false));
        }

        String requestedInterval = interval == null || interval.isBlank()
                ? properties.getInterval()
                : interval;
        List<HistoricalCandle> candles = historicalCandleService.getCandles(
                symbol,
                requestedInterval,
                properties.getAdjustment(),
                startDate,
                endDate
        );

        return ResponseEntity.ok(new HistoricalCandlesResponse(
                symbol.toUpperCase(),
                requestedInterval,
                properties.getAdjustment(),
                startDate,
                endDate,
                candles.size(),
                candles
        ));
    }

    public record HistoricalCandlesResponse(
            String symbol,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate,
            int count,
            List<HistoricalCandle> candles
    ) {
    }
}
