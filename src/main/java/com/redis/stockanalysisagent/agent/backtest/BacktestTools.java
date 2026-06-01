package com.redis.stockanalysisagent.agent.backtest;

import com.redis.stockanalysisagent.backtest.BacktestReport;
import com.redis.stockanalysisagent.backtest.BacktestTrade;
import com.redis.stockanalysisagent.backtest.HistoricalCandleService;
import com.redis.stockanalysisagent.stock.HistoricalCandle;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class BacktestTools {

    private static final int DEFAULT_SHORT_WINDOW = 20;
    private static final int DEFAULT_LONG_WINDOW = 50;
    private static final BigDecimal DEFAULT_INITIAL_CASH = BigDecimal.valueOf(10_000);
    private static final int MONEY_SCALE = 2;
    private static final int CALCULATION_SCALE = 8;

    private final HistoricalCandleService historicalCandleService;

    public BacktestTools(HistoricalCandleService historicalCandleService) {
        this.historicalCandleService = historicalCandleService;
    }

    @Tool(description = "Run a deterministic daily SMA crossover backtest using adjusted historical candles.")
    public BacktestReport runSmaCrossoverBacktest(
            @ToolParam(description = "The stock ticker symbol in uppercase, for example AAPL.")
            String ticker,
            @ToolParam(description = "Backtest start date in yyyy-MM-dd format.")
            String startDate,
            @ToolParam(description = "Backtest end date in yyyy-MM-dd format.")
            String endDate,
            @ToolParam(description = "Short SMA window. Use 20 when the user does not specify one.")
            Integer shortWindow,
            @ToolParam(description = "Long SMA window. Use 50 when the user does not specify one.")
            Integer longWindow,
            @ToolParam(description = "Starting cash. Use 10000 when the user does not specify one.")
            BigDecimal initialCash
    ) {
        String symbol = normalizeTicker(ticker);
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        int shortPeriod = shortWindow == null ? DEFAULT_SHORT_WINDOW : shortWindow;
        int longPeriod = longWindow == null ? DEFAULT_LONG_WINDOW : longWindow;
        BigDecimal startingCash = initialCash == null ? DEFAULT_INITIAL_CASH : initialCash;
        validateInputs(start, end, shortPeriod, longPeriod, startingCash);

        List<HistoricalCandle> candles = historicalCandleService.getCandles(symbol, start, end);
        if (candles.size() < longPeriod + 1) {
            throw new IllegalStateException("Not enough daily candles for the requested SMA windows.");
        }

        List<BacktestTrade> trades = new ArrayList<>();
        BigDecimal cash = startingCash;
        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal peakValue = startingCash;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (int signalIndex = longPeriod; signalIndex < candles.size() - 1; signalIndex++) {
            HistoricalCandle signalCandle = candles.get(signalIndex);
            HistoricalCandle executionCandle = candles.get(signalIndex + 1);
            BigDecimal previousShortSma = sma(candles, signalIndex - 1, shortPeriod);
            BigDecimal previousLongSma = sma(candles, signalIndex - 1, longPeriod);
            BigDecimal currentShortSma = sma(candles, signalIndex, shortPeriod);
            BigDecimal currentLongSma = sma(candles, signalIndex, longPeriod);

            if (shares.signum() == 0
                    && previousShortSma.compareTo(previousLongSma) <= 0
                    && currentShortSma.compareTo(currentLongSma) > 0) {
                BigDecimal price = executionCandle.close();
                shares = cash.divide(price, CALCULATION_SCALE, RoundingMode.DOWN);
                cash = BigDecimal.ZERO;
                trades.add(trade(
                        executionCandle.date(),
                        "BUY",
                        price,
                        shares,
                        portfolioValue(cash, shares, price),
                        "Short SMA crossed above long SMA after " + signalCandle.date()
                ));
            } else if (shares.signum() > 0
                    && previousShortSma.compareTo(previousLongSma) >= 0
                    && currentShortSma.compareTo(currentLongSma) < 0) {
                BigDecimal price = executionCandle.close();
                cash = shares.multiply(price);
                trades.add(trade(
                        executionCandle.date(),
                        "SELL",
                        price,
                        shares,
                        cash,
                        "Short SMA crossed below long SMA after " + signalCandle.date()
                ));
                shares = BigDecimal.ZERO;
            }

            BigDecimal value = portfolioValue(cash, shares, executionCandle.close());
            if (value.compareTo(peakValue) > 0) {
                peakValue = value;
            }
            BigDecimal drawdown = percent(peakValue.subtract(value), peakValue);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        HistoricalCandle finalCandle = candles.getLast();
        BigDecimal finalValue = portfolioValue(cash, shares, finalCandle.close()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal buyAndHoldValue = startingCash
                .divide(candles.getFirst().close(), CALCULATION_SCALE, RoundingMode.DOWN)
                .multiply(finalCandle.close());

        return new BacktestReport(
                symbol,
                "SMA crossover %d/%d".formatted(shortPeriod, longPeriod),
                "1day",
                "all",
                start,
                end,
                startingCash.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                finalValue,
                percent(finalValue.subtract(startingCash), startingCash),
                percent(buyAndHoldValue.subtract(startingCash), startingCash),
                maxDrawdown.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                candles.size(),
                trades.size(),
                "Adjusted daily candles. Signals are calculated after the close and executed at the next available close. No fees, slippage, taxes, or liquidity limits. Fractional shares are allowed.",
                List.copyOf(trades)
        );
    }

    private BacktestTrade trade(
            LocalDate date,
            String action,
            BigDecimal price,
            BigDecimal shares,
            BigDecimal portfolioValue,
            String reason
    ) {
        return new BacktestTrade(
                date,
                action,
                price.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                shares.setScale(4, RoundingMode.HALF_UP),
                portfolioValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                reason
        );
    }

    private BigDecimal portfolioValue(BigDecimal cash, BigDecimal shares, BigDecimal close) {
        return cash.add(shares.multiply(close));
    }

    private BigDecimal sma(List<HistoricalCandle> candles, int endIndex, int period) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            sum = sum.add(candles.get(i).close());
        }
        return sum.divide(BigDecimal.valueOf(period), CALCULATION_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal value, BigDecimal base) {
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return value
                .multiply(BigDecimal.valueOf(100))
                .divide(base, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required.");
        }
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private LocalDate parseDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return LocalDate.parse(value.trim());
    }

    private void validateInputs(
            LocalDate startDate,
            LocalDate endDate,
            int shortWindow,
            int longWindow,
            BigDecimal initialCash
    ) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate.");
        }
        if (shortWindow < 1) {
            throw new IllegalArgumentException("shortWindow must be positive.");
        }
        if (longWindow < 2) {
            throw new IllegalArgumentException("longWindow must be greater than 1.");
        }
        if (shortWindow >= longWindow) {
            throw new IllegalArgumentException("shortWindow must be less than longWindow.");
        }
        if (initialCash.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("initialCash must be positive.");
        }
    }
}
