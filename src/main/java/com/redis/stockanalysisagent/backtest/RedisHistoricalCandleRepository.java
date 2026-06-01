package com.redis.stockanalysisagent.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.redis.stockanalysisagent.stock.HistoricalCandle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class RedisHistoricalCandleRepository implements HistoricalCandleRepository {

    private static final String KEY_PREFIX = "stock-analysis:candles";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisHistoricalCandleRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public Set<LocalDate> fetchedDates(
            String symbol,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Set<String> values = redisTemplate.opsForZSet().rangeByScore(
                coverageKey(symbol, interval, adjustment),
                score(startDate),
                score(endDate)
        );

        if (values == null || values.isEmpty()) {
            return Set.of();
        }

        Set<LocalDate> dates = new HashSet<>();
        values.forEach(value -> dates.add(LocalDate.parse(value)));
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
            redisTemplate.opsForZSet().add(
                    coverageKey(symbol, interval, adjustment),
                    date.toString(),
                    score(date)
            );
        }
    }

    @Override
    public void saveCandles(List<HistoricalCandle> candles) {
        for (HistoricalCandle candle : candles) {
            try {
                redisTemplate.opsForValue().set(candleKey(
                                candle.symbol(),
                                candle.interval(),
                                candle.adjustment(),
                                candle.date()
                        ),
                        objectMapper.writeValueAsString(candle)
                );
                redisTemplate.opsForZSet().add(
                        indexKey(candle.symbol(), candle.interval(), candle.adjustment()),
                        candle.date().toString(),
                        score(candle.date())
                );
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Could not serialize historical candle.", ex);
            }
        }
    }

    @Override
    public List<HistoricalCandle> findCandles(
            String symbol,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Set<String> dates = redisTemplate.opsForZSet().rangeByScore(
                indexKey(symbol, interval, adjustment),
                score(startDate),
                score(endDate)
        );

        if (dates == null || dates.isEmpty()) {
            return List.of();
        }

        List<String> keys = dates.stream()
                .map(date -> candleKey(symbol, interval, adjustment, LocalDate.parse(date)))
                .toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<HistoricalCandle> candles = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                candles.add(objectMapper.readValue(value, HistoricalCandle.class));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Could not deserialize historical candle.", ex);
            }
        }

        return candles.stream()
                .sorted(Comparator.comparing(HistoricalCandle::date))
                .toList();
    }

    static String candleKey(String symbol, String interval, String adjustment, LocalDate date) {
        return "%s:%s:%s:%s:%s".formatted(
                KEY_PREFIX,
                symbol,
                interval,
                adjustment,
                date
        );
    }

    static String indexKey(String symbol, String interval, String adjustment) {
        return "%s:index:%s:%s:%s".formatted(KEY_PREFIX, symbol, interval, adjustment);
    }

    static String coverageKey(String symbol, String interval, String adjustment) {
        return "%s:coverage:%s:%s:%s".formatted(KEY_PREFIX, symbol, interval, adjustment);
    }

    static double score(LocalDate date) {
        return date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
