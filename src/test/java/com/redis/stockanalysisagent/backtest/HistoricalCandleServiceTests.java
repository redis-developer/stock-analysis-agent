package com.redis.stockanalysisagent.backtest;

import com.redis.stockanalysisagent.cache.CacheNames;
import com.redis.stockanalysisagent.cache.ExternalApiUsageService;
import com.redis.stockanalysisagent.providers.HistoricalCandleProvider;
import com.redis.stockanalysisagent.stock.HistoricalCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HistoricalCandleServiceTests {

    private final HistoricalCandleProperties properties = new HistoricalCandleProperties();
    private final InMemoryHistoricalCandleRepository repository = new InMemoryHistoricalCandleRepository();
    private final RecordingHistoricalCandleProvider provider = new RecordingHistoricalCandleProvider();
    private final ExternalApiUsageService apiUsageService = mock(ExternalApiUsageService.class);
    private final HistoricalCandleService service = new HistoricalCandleService(
            provider,
            repository,
            properties,
            apiUsageService
    );

    @Test
    void fetchesAndStoresUncachedRange() {
        List<HistoricalCandle> candles = service.getCandles(
                "aapl",
                "1day",
                "all",
                LocalDate.parse("2025-05-01"),
                LocalDate.parse("2025-05-03")
        );

        assertThat(provider.requests)
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.symbol()).isEqualTo("AAPL");
                    assertThat(request.startDate()).isEqualTo(LocalDate.parse("2025-05-01"));
                    assertThat(request.endDate()).isEqualTo(LocalDate.parse("2025-05-03"));
                });
        assertThat(candles).extracting(HistoricalCandle::date)
                .containsExactly(
                        LocalDate.parse("2025-05-01"),
                        LocalDate.parse("2025-05-02"),
                        LocalDate.parse("2025-05-03")
                );
        assertThat(repository.fetchedDates("AAPL", "1day", "all",
                LocalDate.parse("2025-05-01"), LocalDate.parse("2025-05-03")))
                .containsExactlyInAnyOrder(
                        LocalDate.parse("2025-05-01"),
                        LocalDate.parse("2025-05-02"),
                        LocalDate.parse("2025-05-03")
                );
        verify(apiUsageService).recordHitForCacheName(CacheNames.HISTORICAL_CANDLES);
    }

    @Test
    void readsFromRedisWhenCoverageAlreadyExists() {
        LocalDate startDate = LocalDate.parse("2025-05-01");
        LocalDate endDate = LocalDate.parse("2025-05-02");
        repository.saveCandles(List.of(
                candle("AAPL", "1day", "all", startDate),
                candle("AAPL", "1day", "all", endDate)
        ));
        repository.saveFetchedRange("AAPL", "1day", "all", startDate, endDate);

        List<HistoricalCandle> candles = service.getCandles("AAPL", "1day", "all", startDate, endDate);

        assertThat(provider.requests).isEmpty();
        assertThat(candles).hasSize(2);
        verify(apiUsageService, never()).recordHitForCacheName(CacheNames.HISTORICAL_CANDLES);
    }

    @Test
    void fetchesOnlyMissingTrailingRange() {
        repository.saveCandles(List.of(candle("AAPL", "1day", "all", LocalDate.parse("2025-05-01"))));
        repository.saveFetchedRange(
                "AAPL",
                "1day",
                "all",
                LocalDate.parse("2025-05-01"),
                LocalDate.parse("2025-05-01")
        );

        service.getCandles(
                "AAPL",
                "1day",
                "all",
                LocalDate.parse("2025-05-01"),
                LocalDate.parse("2025-05-03")
        );

        assertThat(provider.requests)
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.startDate()).isEqualTo(LocalDate.parse("2025-05-02"));
                    assertThat(request.endDate()).isEqualTo(LocalDate.parse("2025-05-03"));
                });
    }

    private static HistoricalCandle candle(String symbol, String interval, String adjustment, LocalDate date) {
        return new HistoricalCandle(
                symbol,
                interval,
                adjustment,
                date,
                date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(101),
                BigDecimal.valueOf(99),
                BigDecimal.valueOf(100),
                1000,
                "test"
        );
    }

    private static class InMemoryHistoricalCandleRepository implements HistoricalCandleRepository {

        private final List<HistoricalCandle> candles = new ArrayList<>();
        private final Set<String> fetched = new HashSet<>();

        @Override
        public Set<LocalDate> fetchedDates(
                String symbol,
                String interval,
                String adjustment,
                LocalDate startDate,
                LocalDate endDate
        ) {
            Set<LocalDate> dates = new HashSet<>();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                if (fetched.contains(key(symbol, interval, adjustment, date))) {
                    dates.add(date);
                }
            }
            return dates;
        }

        @Override
        public void saveFetchedRange(
                String symbol,
                String interval,
                String adjustment,
                LocalDate startDate,
                LocalDate endDate
        ) {
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                fetched.add(key(symbol, interval, adjustment, date));
            }
        }

        @Override
        public void saveCandles(List<HistoricalCandle> candles) {
            this.candles.addAll(candles);
        }

        @Override
        public List<HistoricalCandle> findCandles(
                String symbol,
                String interval,
                String adjustment,
                LocalDate startDate,
                LocalDate endDate
        ) {
            return candles.stream()
                    .filter(candle -> candle.symbol().equals(symbol))
                    .filter(candle -> candle.interval().equals(interval))
                    .filter(candle -> candle.adjustment().equals(adjustment))
                    .filter(candle -> !candle.date().isBefore(startDate))
                    .filter(candle -> !candle.date().isAfter(endDate))
                    .sorted(Comparator.comparing(HistoricalCandle::date))
                    .toList();
        }

        private String key(String symbol, String interval, String adjustment, LocalDate date) {
            return "%s:%s:%s:%s".formatted(symbol, interval, adjustment, date);
        }
    }

    private static class RecordingHistoricalCandleProvider implements HistoricalCandleProvider {

        private final List<Request> requests = new ArrayList<>();

        @Override
        public List<HistoricalCandle> fetchCandles(
                String ticker,
                String interval,
                String adjustment,
                LocalDate startDate,
                LocalDate endDate
        ) {
            requests.add(new Request(ticker, interval, adjustment, startDate, endDate));
            List<HistoricalCandle> candles = new ArrayList<>();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                candles.add(candle(ticker, interval, adjustment, date));
            }
            return candles;
        }
    }

    private record Request(
            String symbol,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }
}
