package com.redis.stockanalysisagent.agent.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BacktestRequestParser {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern BACKTEST_TICKER_PATTERN = Pattern.compile("(?i)\\bbacktest\\s+([A-Z]{1,5})\\b");
    private static final Pattern UPPERCASE_TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}\\b");
    private static final Pattern SMA_PATTERN = Pattern.compile("(?i)\\b(?:sma|ma)\\s*(\\d{1,3}).*?\\b(?:sma|ma)?\\s*(\\d{1,3})\\b");
    private static final Pattern DAY_WINDOW_PATTERN = Pattern.compile("(?i)\\b(\\d{1,3})\\s*(?:day|d)?\\s*(?:and|/)\\s*(\\d{1,3})\\s*(?:day|d)?\\s*(?:moving average|sma|ma)");
    private static final Pattern CASH_PATTERN = Pattern.compile("(?i)\\b(?:with\\s+)?(?:\\$)?(\\d+(?:\\.\\d+)?)\\s*(?:starting cash|initial cash|cash)\\b");
    private static final Set<String> NON_TICKERS = Set.of(
            "A",
            "AN",
            "AND",
            "BACKTEST",
            "CASH",
            "DAY",
            "FROM",
            "MA",
            "SMA",
            "THE",
            "TO",
            "USD",
            "USING",
            "WITH"
    );

    private BacktestRequestParser() {
    }

    public static boolean isBacktestRequest(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("backtest");
    }

    public static Optional<ParsedBacktestRequest> parse(String ticker, String message) {
        if (!isBacktestRequest(message)) {
            return Optional.empty();
        }

        String symbol = normalizeTicker(ticker).or(() -> tickerFromMessage(message)).orElse(null);
        List<LocalDate> dates = dates(message);
        if (symbol == null || dates.size() < 2) {
            return Optional.empty();
        }

        WindowPair windows = windows(message);
        return Optional.of(new ParsedBacktestRequest(
                symbol,
                dates.get(0),
                dates.get(1),
                windows.shortWindow(),
                windows.longWindow(),
                cash(message)
        ));
    }

    public static boolean hasMinimumInputs(String message) {
        return parse(null, message).isPresent();
    }

    public static String missingInputPrompt() {
        return "Please include a ticker, start date, end date, and strategy rule. Example: Backtest AAPL from 2020-01-01 to 2025-12-31 using SMA 20 and SMA 50.";
    }

    private static Optional<String> normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return Optional.empty();
        }
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        return NON_TICKERS.contains(normalized) ? Optional.empty() : Optional.of(normalized);
    }

    private static Optional<String> tickerFromMessage(String message) {
        Matcher explicit = BACKTEST_TICKER_PATTERN.matcher(message);
        if (explicit.find()) {
            return normalizeTicker(explicit.group(1));
        }

        Matcher uppercase = UPPERCASE_TICKER_PATTERN.matcher(message);
        while (uppercase.find()) {
            Optional<String> ticker = normalizeTicker(uppercase.group());
            if (ticker.isPresent()) {
                return ticker;
            }
        }

        return Optional.empty();
    }

    private static List<LocalDate> dates(String message) {
        Matcher matcher = DATE_PATTERN.matcher(message);
        return matcher.results()
                .map(result -> LocalDate.parse(result.group()))
                .limit(2)
                .toList();
    }

    private static WindowPair windows(String message) {
        Matcher dayWindowMatcher = DAY_WINDOW_PATTERN.matcher(message);
        if (dayWindowMatcher.find()) {
            return normalizedWindows(
                    Integer.parseInt(dayWindowMatcher.group(1)),
                    Integer.parseInt(dayWindowMatcher.group(2))
            );
        }

        Matcher smaMatcher = SMA_PATTERN.matcher(message);
        if (smaMatcher.find()) {
            return normalizedWindows(
                    Integer.parseInt(smaMatcher.group(1)),
                    Integer.parseInt(smaMatcher.group(2))
            );
        }

        return new WindowPair(20, 50);
    }

    private static WindowPair normalizedWindows(int first, int second) {
        return first <= second
                ? new WindowPair(first, second)
                : new WindowPair(second, first);
    }

    private static BigDecimal cash(String message) {
        Matcher matcher = CASH_PATTERN.matcher(message);
        if (!matcher.find()) {
            return BigDecimal.valueOf(10_000);
        }

        return new BigDecimal(matcher.group(1));
    }

    private record WindowPair(
            int shortWindow,
            int longWindow
    ) {
    }

    public record ParsedBacktestRequest(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            int shortWindow,
            int longWindow,
            BigDecimal initialCash
    ) {
    }
}
