package com.redis.stockanalysisagent.agent.coordinator;

import com.redis.stockanalysisagent.agent.AgentExecution;
import com.redis.stockanalysisagent.agent.AgentType;
import com.redis.stockanalysisagent.agent.backtest.BacktestAgent;
import com.redis.stockanalysisagent.agent.backtest.BacktestResult;
import com.redis.stockanalysisagent.agent.fundamentals.FundamentalsAgent;
import com.redis.stockanalysisagent.agent.fundamentals.FundamentalsResult;
import com.redis.stockanalysisagent.agent.marketdata.MarketDataAgent;
import com.redis.stockanalysisagent.agent.marketdata.MarketDataResult;
import com.redis.stockanalysisagent.agent.news.NewsAgent;
import com.redis.stockanalysisagent.agent.news.NewsResult;
import com.redis.stockanalysisagent.agent.synthesis.SynthesisAgent;
import com.redis.stockanalysisagent.agent.synthesis.SynthesisEvidence;
import com.redis.stockanalysisagent.agent.synthesis.SynthesisResult;
import com.redis.stockanalysisagent.agent.technicalanalysis.TechnicalAnalysisAgent;
import com.redis.stockanalysisagent.agent.technicalanalysis.TechnicalAnalysisResult;
import com.redis.stockanalysisagent.backtest.BacktestReport;
import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.stock.FundamentalsSnapshot;
import com.redis.stockanalysisagent.stock.MarketSnapshot;
import com.redis.stockanalysisagent.stock.NewsItem;
import com.redis.stockanalysisagent.stock.NewsSnapshot;
import com.redis.stockanalysisagent.stock.TechnicalAnalysisSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CoordinatorAgentToolsTests {

    private final MarketDataAgent marketDataAgent = mock(MarketDataAgent.class);
    private final FundamentalsAgent fundamentalsAgent = mock(FundamentalsAgent.class);
    private final NewsAgent newsAgent = mock(NewsAgent.class);
    private final TechnicalAnalysisAgent technicalAnalysisAgent = mock(TechnicalAnalysisAgent.class);
    private final BacktestAgent backtestAgent = mock(BacktestAgent.class);
    private final SynthesisAgent synthesisAgent = mock(SynthesisAgent.class);
    private final ExternalDataCache externalDataCache = mock(ExternalDataCache.class);
    private final ChatProgressPublisher progressPublisher = mock(ChatProgressPublisher.class);
    private final CoordinatorAgentTools tools = new CoordinatorAgentTools(
            marketDataAgent,
            fundamentalsAgent,
            newsAgent,
            technicalAnalysisAgent,
            backtestAgent,
            synthesisAgent,
            externalDataCache,
            progressPublisher
    );

    @Test
    void runSynthesisAgentUsesSpecialistEvidenceFromTrace() {
        String question = "Give me a full analysis of Apple.";
        MarketSnapshot marketSnapshot = marketSnapshot();
        FundamentalsSnapshot fundamentalsSnapshot = fundamentalsSnapshot();
        NewsSnapshot newsSnapshot = newsSnapshot();
        TechnicalAnalysisSnapshot technicalSnapshot = technicalSnapshot();
        when(externalDataCache.drainRecordedAccesses()).thenReturn(List.of());
        when(marketDataAgent.execute("AAPL", question))
                .thenReturn(MarketDataResult.completed("Market data complete.", marketSnapshot));
        when(fundamentalsAgent.execute("AAPL", question, marketSnapshot))
                .thenReturn(FundamentalsResult.completed("Fundamentals complete.", fundamentalsSnapshot));
        when(newsAgent.execute("AAPL", question))
                .thenReturn(NewsResult.completed("News complete.", newsSnapshot));
        when(technicalAnalysisAgent.execute("AAPL", question))
                .thenReturn(TechnicalAnalysisResult.completed("Technical analysis complete.", technicalSnapshot));
        when(synthesisAgent.execute(
                eq(question),
                eq(List.of("AAPL")),
                argThat(this::containsAppleEvidence)
        )).thenReturn(SynthesisResult.completed("Synthesized AAPL evidence.", "Full Apple analysis."));

        tools.startTrace();
        try {
            tools.runMarketDataAgent("AAPL", question);
            tools.runFundamentalsAgent("AAPL", question);
            tools.runNewsAgent("AAPL", question);
            tools.runTechnicalAnalysisAgent("AAPL", question);

            CoordinatorAgentTools.AgentToolResult result = tools.runSynthesisAgent("AAPL", question);

            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(result.finalResponse()).isEqualTo("Full Apple analysis.");
            assertThat(tools.drainExecutions())
                    .extracting(AgentExecution::agentType)
                    .containsExactly(
                            AgentType.MARKET_DATA,
                            AgentType.FUNDAMENTALS,
                            AgentType.NEWS,
                            AgentType.TECHNICAL_ANALYSIS,
                            AgentType.SYNTHESIS
                    );
            verify(synthesisAgent).execute(eq(question), eq(List.of("AAPL")), argThat(this::containsAppleEvidence));
        } finally {
            tools.clearTrace();
        }
    }

    @Test
    void runBacktestAgentNormalizesTickerAndRecordsExecution() {
        BacktestReport report = backtestReport();
        when(externalDataCache.drainRecordedAccesses()).thenReturn(List.of());
        when(backtestAgent.execute("AAPL", "Backtest AAPL from 2025 to 2026."))
                .thenReturn(BacktestResult.completed("Backtest complete.", report));

        tools.startTrace();
        try {
            CoordinatorAgentTools.AgentToolResult result = tools.runBacktestAgent(
                    "aapl",
                    "Backtest AAPL from 2025 to 2026."
            );

            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(result.finalResponse()).isSameAs(report);
            assertThat(tools.drainExecutions())
                    .singleElement()
                    .satisfies(execution -> {
                        assertThat(execution.agentType()).isEqualTo(AgentType.BACKTEST);
                        assertThat(execution.ticker()).isEqualTo("AAPL");
                        assertThat(execution.summary()).isEqualTo("Backtest complete.");
                    });
            verify(backtestAgent).execute("AAPL", "Backtest AAPL from 2025 to 2026.");
        } finally {
            tools.clearTrace();
        }
    }

    @Test
    void runSynthesisAgentSkipsWhenNoSpecialistEvidenceExists() {
        tools.startTrace();
        try {
            CoordinatorAgentTools.AgentToolResult result = tools.runSynthesisAgent("AAPL", "Analyze Apple.");

            assertThat(result.status()).isEqualTo("ERROR");
            assertThat(result.message()).isEqualTo("Synthesis skipped because no specialist evidence is available.");
            assertThat(tools.drainExecutions())
                    .singleElement()
                    .satisfies(execution -> {
                        assertThat(execution.agentType()).isEqualTo(AgentType.SYNTHESIS);
                        assertThat(execution.ticker()).isEqualTo("AAPL");
                    });
            verifyNoInteractions(synthesisAgent);
        } finally {
            tools.clearTrace();
        }
    }

    @Test
    void runSynthesisAgentUsesRecoveredEvidenceFromReplayMessage() {
        String question = "Give me a full view on NVIDIA.";
        when(synthesisAgent.execute(
                eq(question),
                eq(List.of("NVDA")),
                argThat(this::containsRecoveredNvidiaEvidence)
        )).thenReturn(SynthesisResult.completed("Synthesized recovered NVDA evidence.", "Full NVIDIA analysis."));

        tools.startTrace("""
                Continue this stock analysis workflow from the latest Redis checkpoint.

                Recovered evidence:
                Specialist evidence: fundamentals
                Step: FUNDAMENTALS:NVDA
                Actor: fundamentals
                Input: agent: FUNDAMENTALS
                ticker: NVDA
                Output: NVIDIA reported revenue of $215.94 billion and strong margins.

                Tool evidence: getTechnicalAnalysisSnapshot
                Step: tool:getTechnicalAnalysisSnapshot:6
                Actor: technical_analysis
                Input: {"ticker":"NVDA"}
                Output: RSI is 50.45 and the trend signal is neutral.

                Checkpoint summary:
                Created checkpoint for tool:runFundamentalsAgent:3.
                """);
        try {
            CoordinatorAgentTools.AgentToolResult result = tools.runSynthesisAgent("NVDA", question);

            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(result.finalResponse()).isEqualTo("Full NVIDIA analysis.");
            verify(synthesisAgent).execute(
                    eq(question),
                    eq(List.of("NVDA")),
                    argThat(this::containsRecoveredNvidiaEvidence)
            );
        } finally {
            tools.clearTrace();
        }
    }

    private boolean containsAppleEvidence(SynthesisEvidence evidence) {
        return evidence != null
                && evidence.marketData().containsKey("AAPL")
                && evidence.fundamentals().containsKey("AAPL")
                && evidence.news().containsKey("AAPL")
                && evidence.technicalAnalysis().containsKey("AAPL");
    }

    private boolean containsRecoveredNvidiaEvidence(SynthesisEvidence evidence) {
        return evidence != null
                && String.valueOf(evidence.fundamentals().get("NVDA")).contains("215.94")
                && String.valueOf(evidence.technicalAnalysis().get("NVDA")).contains("50.45");
    }

    private MarketSnapshot marketSnapshot() {
        return new MarketSnapshot(
                "AAPL",
                BigDecimal.valueOf(311.51),
                BigDecimal.valueOf(308.33),
                BigDecimal.valueOf(3.18),
                BigDecimal.valueOf(1.03),
                OffsetDateTime.parse("2026-05-27T12:00:00Z"),
                "twelve-data"
        );
    }

    private FundamentalsSnapshot fundamentalsSnapshot() {
        return new FundamentalsSnapshot(
                "AAPL",
                "Apple Inc.",
                "0000320193",
                BigDecimal.valueOf(416_160_000_000L),
                BigDecimal.valueOf(391_040_000_000L),
                BigDecimal.valueOf(6.43),
                BigDecimal.valueOf(112_010_000_000L),
                BigDecimal.valueOf(133_040_000_000L),
                BigDecimal.valueOf(31.97),
                BigDecimal.valueOf(26.92),
                BigDecimal.valueOf(35_930_000_000L),
                BigDecimal.valueOf(85_000_000_000L),
                BigDecimal.valueOf(15_000_000_000L),
                BigDecimal.valueOf(311.51),
                BigDecimal.valueOf(4_672_650_000_000L),
                BigDecimal.valueOf(11.23),
                BigDecimal.valueOf(7.46),
                BigDecimal.valueOf(41.76),
                LocalDate.parse("2025-09-27"),
                LocalDate.parse("2025-10-31"),
                "sec"
        );
    }

    private NewsSnapshot newsSnapshot() {
        return new NewsSnapshot(
                "AAPL",
                "Apple Inc.",
                List.of(new NewsItem(
                        LocalDate.parse("2026-05-01"),
                        "SEC",
                        "10-Q",
                        "Quarterly report",
                        "Quarterly financial filing.",
                        "https://www.sec.gov"
                )),
                List.of(),
                null,
                "sec"
        );
    }

    private TechnicalAnalysisSnapshot technicalSnapshot() {
        return new TechnicalAnalysisSnapshot(
                "AAPL",
                "1day",
                OffsetDateTime.parse("2026-05-27T12:00:00Z"),
                BigDecimal.valueOf(311.44),
                BigDecimal.valueOf(293.42),
                BigDecimal.valueOf(294.76),
                BigDecimal.valueOf(79.08),
                "BULLISH",
                "OVERBOUGHT",
                "twelve-data"
        );
    }

    private BacktestReport backtestReport() {
        return new BacktestReport(
                "AAPL",
                "SMA crossover 20/50",
                "1day",
                "all",
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-12-31"),
                BigDecimal.valueOf(10_000),
                BigDecimal.valueOf(11_000),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(8),
                252,
                2,
                "Adjusted daily candles.",
                List.of()
        );
    }
}
