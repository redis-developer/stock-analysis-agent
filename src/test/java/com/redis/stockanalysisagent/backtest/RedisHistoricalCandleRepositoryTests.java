package com.redis.stockanalysisagent.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisHistoricalCandleRepositoryTests {

    @Test
    void buildsStablePerDayKeys() {
        assertThat(RedisHistoricalCandleRepository.candleKey(
                "AAPL",
                "1day",
                "all",
                LocalDate.parse("2025-05-30")
        )).isEqualTo("stock-analysis:candles:AAPL:1day:all:2025-05-30");
        assertThat(RedisHistoricalCandleRepository.indexKey("AAPL", "1day", "all"))
                .isEqualTo("stock-analysis:candles:index:AAPL:1day:all");
        assertThat(RedisHistoricalCandleRepository.coverageKey("AAPL", "1day", "all"))
                .isEqualTo("stock-analysis:candles:coverage:AAPL:1day:all");
    }

    @Test
    void usesUtcMidnightEpochMillisAsScore() {
        assertThat(RedisHistoricalCandleRepository.score(LocalDate.parse("1970-01-02")))
                .isEqualTo(86_400_000d);
    }
}
