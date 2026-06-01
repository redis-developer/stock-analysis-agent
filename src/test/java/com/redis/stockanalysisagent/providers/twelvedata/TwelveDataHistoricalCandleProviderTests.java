package com.redis.stockanalysisagent.providers.twelvedata;

import com.redis.stockanalysisagent.stock.HistoricalCandle;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwelveDataHistoricalCandleProviderTests {

    @Test
    void requestsAndParsesHistoricalCandles() {
        RestClient.Builder builder = RestClient.builder();
        var server = bindTo(builder).build();
        TwelveDataProperties properties = properties();
        TwelveDataHistoricalCandleProvider provider = new TwelveDataHistoricalCandleProvider(builder, properties);
        server.expect(request -> {
            var uri = request.getURI();
            var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            assertThat(uri.getPath()).isEqualTo("/time_series");
            assertThat(params.getFirst("symbol")).isEqualTo("AAPL");
            assertThat(params.getFirst("interval")).isEqualTo("1day");
            assertThat(params.getFirst("start_date")).isEqualTo("2025-05-01");
            assertThat(params.getFirst("end_date")).isEqualTo("2025-05-02");
            assertThat(params.getFirst("outputsize")).isEqualTo("5000");
            assertThat(params.getFirst("order")).isEqualTo("asc");
            assertThat(params.getFirst("adjust")).isEqualTo("all");
            assertThat(params.getFirst("apikey")).isEqualTo("test-api-key");
        }).andRespond(withSuccess("""
                {
                  "meta": {
                    "symbol": "AAPL",
                    "interval": "1day"
                  },
                  "values": [
                    {
                      "datetime": "2025-05-01",
                      "open": "100.10",
                      "high": "101.20",
                      "low": "99.30",
                      "close": "100.50",
                      "volume": "123456"
                    },
                    {
                      "datetime": "2025-05-02",
                      "open": "101.10",
                      "high": "102.20",
                      "low": "100.30",
                      "close": "101.50",
                      "volume": "234567"
                    }
                  ],
                  "status": "ok"
                }
                """, MediaType.APPLICATION_JSON));

        var candles = provider.fetchCandles(
                "aapl",
                "1day",
                "all",
                LocalDate.parse("2025-05-01"),
                LocalDate.parse("2025-05-02")
        );

        assertThat(candles).hasSize(2);
        assertThat(candles.getFirst())
                .satisfies(candle -> {
                    assertThat(candle.symbol()).isEqualTo("AAPL");
                    assertThat(candle.interval()).isEqualTo("1day");
                    assertThat(candle.adjustment()).isEqualTo("all");
                    assertThat(candle.date()).isEqualTo(LocalDate.parse("2025-05-01"));
                    assertThat(candle.open()).isEqualByComparingTo("100.10");
                    assertThat(candle.high()).isEqualByComparingTo("101.20");
                    assertThat(candle.low()).isEqualByComparingTo("99.30");
                    assertThat(candle.close()).isEqualByComparingTo("100.50");
                    assertThat(candle.volume()).isEqualTo(123456);
                    assertThat(candle.source()).isEqualTo("twelve-data");
                });
        server.verify();
    }

    @Test
    void throwsOnTwelveDataErrorResponse() {
        RestClient.Builder builder = RestClient.builder();
        var server = bindTo(builder).build();
        TwelveDataHistoricalCandleProvider provider = new TwelveDataHistoricalCandleProvider(builder, properties());
        server.expect(request -> {
        }).andRespond(withSuccess("""
                {
                  "status": "error",
                  "message": "API limit reached"
                }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.fetchCandles(
                "AAPL",
                "1day",
                "all",
                LocalDate.parse("2025-05-01"),
                LocalDate.parse("2025-05-02")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API limit reached");
        server.verify();
    }

    private static TwelveDataProperties properties() {
        TwelveDataProperties properties = new TwelveDataProperties();
        properties.setBaseUrl(java.net.URI.create("https://twelve.example.test"));
        properties.setApiKey("test-api-key");
        return properties;
    }
}
