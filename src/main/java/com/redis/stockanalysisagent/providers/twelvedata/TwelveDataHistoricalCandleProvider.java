package com.redis.stockanalysisagent.providers.twelvedata;

import com.redis.stockanalysisagent.providers.HistoricalCandleProvider;
import com.redis.stockanalysisagent.reliability.circuitbreaker.CircuitBreakerService;
import com.redis.stockanalysisagent.stock.HistoricalCandle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;

@Component
@ConditionalOnProperty(
        prefix = "stock-analysis.historical-candles",
        name = "provider",
        havingValue = "twelve-data",
        matchIfMissing = true
)
public class TwelveDataHistoricalCandleProvider implements HistoricalCandleProvider {

    private static final int MAX_OUTPUT_SIZE = 5000;

    private final RestClient restClient;
    private final TwelveDataProperties properties;
    private final CircuitBreakerService circuitBreakerService;

    @Autowired
    public TwelveDataHistoricalCandleProvider(
            RestClient.Builder restClientBuilder,
            TwelveDataProperties properties,
            CircuitBreakerService circuitBreakerService
    ) {
        this.properties = properties;
        this.circuitBreakerService = circuitBreakerService;
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl().toString())
                .build();
    }

    TwelveDataHistoricalCandleProvider(
            RestClient.Builder restClientBuilder,
            TwelveDataProperties properties
    ) {
        this(restClientBuilder, properties, null);
    }

    @Override
    public List<HistoricalCandle> fetchCandles(
            String ticker,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("""
                    Twelve Data historical candles are enabled, but no API key is configured.
                    Set STOCK_ANALYSIS_AGENT_TWELVE_DATA_API_KEY or stock-analysis.market-data.twelve-data.api-key.
                    """.stripIndent().trim());
        }

        if (circuitBreakerService == null) {
            return fetchCandlesFromProvider(ticker, interval, adjustment, startDate, endDate);
        }

        return circuitBreakerService.call("twelve-data", () ->
                fetchCandlesFromProvider(ticker, interval, adjustment, startDate, endDate));
    }

    private List<HistoricalCandle> fetchCandlesFromProvider(
            String ticker,
            String interval,
            String adjustment,
            LocalDate startDate,
            LocalDate endDate
    ) {
        String symbol = ticker.toUpperCase(Locale.ROOT);
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/time_series")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("start_date", startDate)
                        .queryParam("end_date", endDate)
                        .queryParam("outputsize", MAX_OUTPUT_SIZE)
                        .queryParam("order", "asc")
                        .queryParam("adjust", adjustment)
                        .queryParam("apikey", properties.getApiKey())
                        .build())
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Twelve Data returned an empty historical time-series response.");
        }

        if ("error".equalsIgnoreCase(optionalText(response, "status", ""))) {
            throw new IllegalStateException("Twelve Data error: " + optionalText(response, "message", "Unknown error"));
        }

        JsonNode valuesNode = response.path("values");
        if (!valuesNode.isArray()) {
            throw new IllegalStateException("Twelve Data historical response is missing values.");
        }

        return StreamSupport.stream(valuesNode.spliterator(), false)
                .map(valueNode -> candle(symbol, interval, adjustment, valueNode))
                .sorted(Comparator.comparing(HistoricalCandle::date))
                .toList();
    }

    private HistoricalCandle candle(String symbol, String interval, String adjustment, JsonNode valueNode) {
        LocalDate date = parseDate(requiredText(valueNode, "datetime"));
        return new HistoricalCandle(
                symbol,
                interval,
                adjustment,
                date,
                date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                decimal(valueNode, "open"),
                decimal(valueNode, "high"),
                decimal(valueNode, "low"),
                decimal(valueNode, "close"),
                decimal(valueNode, "volume").longValue(),
                "twelve-data"
        );
    }

    private BigDecimal decimal(JsonNode node, String fieldName) {
        return new BigDecimal(requiredText(node, fieldName));
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull() || field.isMissingNode() || field.asText().isBlank()) {
            throw new IllegalStateException("Twelve Data historical response is missing field " + fieldName + ".");
        }
        return field.asText().trim();
    }

    private String optionalText(JsonNode node, String fieldName, String defaultValue) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull() || field.isMissingNode()) {
            return defaultValue;
        }
        return field.asText(defaultValue);
    }

    private LocalDate parseDate(String value) {
        return LocalDate.parse(value.substring(0, 10));
    }
}
