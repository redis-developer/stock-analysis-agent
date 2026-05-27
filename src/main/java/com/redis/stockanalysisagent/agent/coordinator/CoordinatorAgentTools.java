package com.redis.stockanalysisagent.agent.coordinator;

import com.redis.stockanalysisagent.agent.AgentExecution;
import com.redis.stockanalysisagent.agent.AgentType;
import com.redis.stockanalysisagent.agent.TokenUsageSummary;
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
import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.cache.ExternalDataAccess;
import com.redis.stockanalysisagent.cache.ExternalDataCache;
import com.redis.stockanalysisagent.stock.MarketSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CoordinatorAgentTools {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorAgentTools.class);

    private final MarketDataAgent marketDataAgent;
    private final FundamentalsAgent fundamentalsAgent;
    private final NewsAgent newsAgent;
    private final TechnicalAnalysisAgent technicalAnalysisAgent;
    private final SynthesisAgent synthesisAgent;
    private final ExternalDataCache externalDataCache;
    private final ChatProgressPublisher progressPublisher;
    private final ThreadLocal<ExecutionTrace> activeTrace = new ThreadLocal<>();

    public CoordinatorAgentTools(
            MarketDataAgent marketDataAgent,
            FundamentalsAgent fundamentalsAgent,
            NewsAgent newsAgent,
            TechnicalAnalysisAgent technicalAnalysisAgent,
            SynthesisAgent synthesisAgent,
            ExternalDataCache externalDataCache,
            ChatProgressPublisher progressPublisher
    ) {
        this.marketDataAgent = marketDataAgent;
        this.fundamentalsAgent = fundamentalsAgent;
        this.newsAgent = newsAgent;
        this.technicalAnalysisAgent = technicalAnalysisAgent;
        this.synthesisAgent = synthesisAgent;
        this.externalDataCache = externalDataCache;
        this.progressPublisher = progressPublisher;
    }

    public void startTrace() {
        activeTrace.set(new ExecutionTrace());
    }

    public List<AgentExecution> drainExecutions() {
        ExecutionTrace trace = activeTrace.get();
        return trace == null ? List.of() : trace.executions();
    }

    public void clearTrace() {
        activeTrace.remove();
        externalDataCache.clearRecordedAccesses();
    }

    @Tool(description = "Run the Market Data Agent for one ticker and return current market data.")
    public AgentToolResult runMarketDataAgent(
            @ToolParam(description = "The stock ticker symbol in uppercase, for example AAPL.")
            String ticker,
            @ToolParam(description = "The user's market data question.")
            String question
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        externalDataCache.clearRecordedAccesses();
        long startedAt = System.nanoTime();
        progressPublisher.running(
                agentStepId(AgentType.MARKET_DATA, normalizedTicker),
                agentProgressLabel(AgentType.MARKET_DATA, normalizedTicker),
                ChatProgressPublisher.KIND_AGENT,
                "Triggering the market data agent."
        );
        try {
            MarketDataResult result = marketDataAgent.execute(normalizedTicker, question);
            List<ExternalDataAccess> accesses = externalDataCache.drainRecordedAccesses();
            trace().storeMarketData(normalizedTicker, result.getFinalResponse());
            trace().storeEvidence(AgentType.MARKET_DATA, normalizedTicker, result.getFinalResponse());
            trace().record(execution(
                    AgentType.MARKET_DATA,
                    normalizedTicker,
                    result.getMessage(),
                    startedAt,
                    result.getTokenUsage(),
                    accesses
            ));
            progressPublisher.completed(
                    agentStepId(AgentType.MARKET_DATA, normalizedTicker),
                    agentProgressLabel(AgentType.MARKET_DATA, normalizedTicker),
                    ChatProgressPublisher.KIND_AGENT,
                    elapsedDurationMs(startedAt),
                    result.getMessage(),
                    accesses
            );
            return AgentToolResult.completed(result.getMessage(), result.getFinalResponse());
        } catch (RuntimeException ex) {
            return errorResult(AgentType.MARKET_DATA, normalizedTicker, startedAt, ex);
        } finally {
            externalDataCache.clearRecordedAccesses();
        }
    }

    @Tool(description = "Run the Fundamentals Agent for one ticker and return financial metrics and valuation context.")
    public AgentToolResult runFundamentalsAgent(
            @ToolParam(description = "The stock ticker symbol in uppercase, for example AAPL.")
            String ticker,
            @ToolParam(description = "The user's fundamentals question.")
            String question
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        externalDataCache.clearRecordedAccesses();
        long startedAt = System.nanoTime();
        progressPublisher.running(
                agentStepId(AgentType.FUNDAMENTALS, normalizedTicker),
                agentProgressLabel(AgentType.FUNDAMENTALS, normalizedTicker),
                ChatProgressPublisher.KIND_AGENT,
                "Triggering the fundamentals agent."
        );
        try {
            FundamentalsResult result = fundamentalsAgent.execute(
                    normalizedTicker,
                    question,
                    trace().marketData(normalizedTicker)
            );
            List<ExternalDataAccess> accesses = externalDataCache.drainRecordedAccesses();
            trace().storeEvidence(AgentType.FUNDAMENTALS, normalizedTicker, result.getFinalResponse());
            trace().record(execution(
                    AgentType.FUNDAMENTALS,
                    normalizedTicker,
                    result.getMessage(),
                    startedAt,
                    result.getTokenUsage(),
                    accesses
            ));
            progressPublisher.completed(
                    agentStepId(AgentType.FUNDAMENTALS, normalizedTicker),
                    agentProgressLabel(AgentType.FUNDAMENTALS, normalizedTicker),
                    ChatProgressPublisher.KIND_AGENT,
                    elapsedDurationMs(startedAt),
                    result.getMessage(),
                    accesses
            );
            return AgentToolResult.completed(result.getMessage(), result.getFinalResponse());
        } catch (RuntimeException ex) {
            return errorResult(AgentType.FUNDAMENTALS, normalizedTicker, startedAt, ex);
        } finally {
            externalDataCache.clearRecordedAccesses();
        }
    }

    @Tool(description = "Run the News Agent for one ticker and return recent filing and web news signals.")
    public AgentToolResult runNewsAgent(
            @ToolParam(description = "The stock ticker symbol in uppercase, for example AAPL.")
            String ticker,
            @ToolParam(description = "The user's news question.")
            String question
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        externalDataCache.clearRecordedAccesses();
        long startedAt = System.nanoTime();
        progressPublisher.running(
                agentStepId(AgentType.NEWS, normalizedTicker),
                agentProgressLabel(AgentType.NEWS, normalizedTicker),
                ChatProgressPublisher.KIND_AGENT,
                "Triggering the news agent."
        );
        try {
            NewsResult result = newsAgent.execute(normalizedTicker, question);
            List<ExternalDataAccess> accesses = externalDataCache.drainRecordedAccesses();
            trace().storeEvidence(AgentType.NEWS, normalizedTicker, result.getFinalResponse());
            trace().record(execution(
                    AgentType.NEWS,
                    normalizedTicker,
                    result.getMessage(),
                    startedAt,
                    result.getTokenUsage(),
                    accesses
            ));
            progressPublisher.completed(
                    agentStepId(AgentType.NEWS, normalizedTicker),
                    agentProgressLabel(AgentType.NEWS, normalizedTicker),
                    ChatProgressPublisher.KIND_AGENT,
                    elapsedDurationMs(startedAt),
                    result.getMessage(),
                    accesses
            );
            return AgentToolResult.completed(result.getMessage(), result.getFinalResponse());
        } catch (RuntimeException ex) {
            return errorResult(AgentType.NEWS, normalizedTicker, startedAt, ex);
        } finally {
            externalDataCache.clearRecordedAccesses();
        }
    }

    @Tool(description = "Run the Technical Analysis Agent for one ticker and return trend, momentum, RSI, and moving average signals.")
    public AgentToolResult runTechnicalAnalysisAgent(
            @ToolParam(description = "The stock ticker symbol in uppercase, for example AAPL.")
            String ticker,
            @ToolParam(description = "The user's technical analysis question.")
            String question
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        externalDataCache.clearRecordedAccesses();
        long startedAt = System.nanoTime();
        progressPublisher.running(
                agentStepId(AgentType.TECHNICAL_ANALYSIS, normalizedTicker),
                agentProgressLabel(AgentType.TECHNICAL_ANALYSIS, normalizedTicker),
                ChatProgressPublisher.KIND_AGENT,
                "Triggering the technical analysis agent."
        );
        try {
            TechnicalAnalysisResult result = technicalAnalysisAgent.execute(normalizedTicker, question);
            List<ExternalDataAccess> accesses = externalDataCache.drainRecordedAccesses();
            trace().storeEvidence(AgentType.TECHNICAL_ANALYSIS, normalizedTicker, result.getFinalResponse());
            trace().record(execution(
                    AgentType.TECHNICAL_ANALYSIS,
                    normalizedTicker,
                    result.getMessage(),
                    startedAt,
                    result.getTokenUsage(),
                    accesses
            ));
            progressPublisher.completed(
                    agentStepId(AgentType.TECHNICAL_ANALYSIS, normalizedTicker),
                    agentProgressLabel(AgentType.TECHNICAL_ANALYSIS, normalizedTicker),
                    ChatProgressPublisher.KIND_AGENT,
                    elapsedDurationMs(startedAt),
                    result.getMessage(),
                    accesses
            );
            return AgentToolResult.completed(result.getMessage(), result.getFinalResponse());
        } catch (RuntimeException ex) {
            return errorResult(AgentType.TECHNICAL_ANALYSIS, normalizedTicker, startedAt, ex);
        } finally {
            externalDataCache.clearRecordedAccesses();
        }
    }

    @Tool(description = "Run the Synthesis Agent only after specialist tools have produced enough evidence for a full analysis, comparison, outlook, risk review, or mixed-signal answer.")
    public AgentToolResult runSynthesisAgent(
            @ToolParam(description = "Comma-separated stock ticker symbols in uppercase, for example AAPL or AAPL,MSFT.")
            String tickers,
            @ToolParam(description = "The user's analysis question that needs synthesis.")
            String question
    ) {
        List<String> normalizedTickers = normalizeTickers(tickers);
        String tickerLabel = tickerLabel(normalizedTickers);
        SynthesisEvidence evidence = trace().synthesisEvidence(normalizedTickers);
        long startedAt = System.nanoTime();
        progressPublisher.running(
                agentStepId(AgentType.SYNTHESIS, tickerLabel),
                agentProgressLabel(AgentType.SYNTHESIS, tickerLabel),
                ChatProgressPublisher.KIND_AGENT,
                "Triggering the synthesis agent."
        );

        if (evidence.isEmpty()) {
            String message = "Synthesis skipped because no specialist evidence is available.";
            trace().record(new AgentExecution(
                    AgentType.SYNTHESIS,
                    tickerLabel,
                    message,
                    elapsedDurationMs(startedAt),
                    null
            ));
            progressPublisher.failed(
                    agentStepId(AgentType.SYNTHESIS, tickerLabel),
                    agentProgressLabel(AgentType.SYNTHESIS, tickerLabel),
                    ChatProgressPublisher.KIND_AGENT,
                    elapsedDurationMs(startedAt),
                    message
            );
            return AgentToolResult.error(message);
        }

        try {
            SynthesisResult result = synthesisAgent.execute(question, normalizedTickers, evidence);
            trace().record(execution(
                    AgentType.SYNTHESIS,
                    tickerLabel,
                    result.getMessage(),
                    startedAt,
                    result.getTokenUsage(),
                    List.of()
            ));
            progressPublisher.completed(
                    agentStepId(AgentType.SYNTHESIS, tickerLabel),
                    agentProgressLabel(AgentType.SYNTHESIS, tickerLabel),
                    ChatProgressPublisher.KIND_AGENT,
                    elapsedDurationMs(startedAt),
                    result.getMessage()
            );
            return AgentToolResult.completed(result.getMessage(), result.getFinalResponse());
        } catch (RuntimeException ex) {
            return errorResult(AgentType.SYNTHESIS, tickerLabel, startedAt, ex);
        }
    }

    private AgentToolResult errorResult(
            AgentType agentType,
            String ticker,
            long startedAt,
            RuntimeException ex
    ) {
        log.warn("{} Agent failed for {}.", agentType, ticker, ex);
        List<ExternalDataAccess> accesses = externalDataCache.drainRecordedAccesses();
        String message = "%s failed for %s: %s".formatted(agentType.name(), ticker, errorMessage(ex));
        trace().record(new AgentExecution(
                agentType,
                ticker,
                message,
                elapsedDurationMs(startedAt),
                null,
                accesses
        ));
        progressPublisher.failed(
                agentStepId(agentType, ticker),
                agentProgressLabel(agentType, ticker),
                ChatProgressPublisher.KIND_AGENT,
                elapsedDurationMs(startedAt),
                message
        );
        return AgentToolResult.error(message);
    }

    private AgentExecution execution(
            AgentType agentType,
            String ticker,
            String message,
            long startedAt,
            TokenUsageSummary tokenUsage,
            List<ExternalDataAccess> dataAccesses
    ) {
        return new AgentExecution(
                agentType,
                ticker,
                message == null || message.isBlank()
                        ? "%s completed for %s.".formatted(agentType.name(), ticker)
                        : message,
                elapsedDurationMs(startedAt),
                tokenUsage,
                dataAccesses
        );
    }

    private ExecutionTrace trace() {
        ExecutionTrace trace = activeTrace.get();
        if (trace == null) {
            trace = new ExecutionTrace();
            activeTrace.set(trace);
        }
        return trace;
    }

    private String normalizeTicker(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase();
    }

    private List<String> normalizeTickers(String tickers) {
        if (tickers == null || tickers.isBlank()) {
            return List.of();
        }

        return Arrays.stream(tickers.split("[,\\s]+"))
                .map(this::normalizeTicker)
                .filter(value -> !value.isBlank())
                .filter(value -> !"AND".equals(value))
                .distinct()
                .toList();
    }

    private String tickerLabel(List<String> tickers) {
        return tickers == null || tickers.isEmpty() ? "" : String.join(",", tickers);
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String agentStepId(AgentType agentType, String ticker) {
        return ticker == null || ticker.isBlank()
                ? agentType.name()
                : agentType.name() + ":" + ticker;
    }

    private String agentProgressLabel(AgentType agentType, String ticker) {
        String agentName = switch (agentType) {
            case MARKET_DATA -> "Market data agent";
            case FUNDAMENTALS -> "Fundamentals agent";
            case NEWS -> "News agent";
            case TECHNICAL_ANALYSIS -> "Technical analysis agent";
            case SYNTHESIS -> "Synthesis agent";
        };

        return ticker == null || ticker.isBlank() ? agentName : agentName + " " + ticker;
    }

    public record AgentToolResult(
            String status,
            String message,
            Object finalResponse
    ) {

        static AgentToolResult completed(String message, Object finalResponse) {
            return new AgentToolResult("COMPLETED", message, finalResponse);
        }

        static AgentToolResult error(String message) {
            return new AgentToolResult("ERROR", message, null);
        }
    }

    private static final class ExecutionTrace {

        private final List<AgentExecution> executions = new ArrayList<>();
        private final Map<String, MarketSnapshot> marketData = new LinkedHashMap<>();
        private final Map<AgentType, Map<String, Object>> evidence = new LinkedHashMap<>();

        private List<AgentExecution> executions() {
            return List.copyOf(executions);
        }

        private void record(AgentExecution execution) {
            executions.add(execution);
        }

        private MarketSnapshot marketData(String ticker) {
            return marketData.get(ticker);
        }

        private void storeMarketData(String ticker, MarketSnapshot snapshot) {
            if (snapshot != null) {
                marketData.put(ticker, snapshot);
            }
        }

        private void storeEvidence(AgentType agentType, String ticker, Object value) {
            if (agentType == null || ticker == null || ticker.isBlank() || value == null) {
                return;
            }

            evidence.computeIfAbsent(agentType, ignored -> new LinkedHashMap<>())
                    .put(ticker, value);
        }

        private SynthesisEvidence synthesisEvidence(List<String> tickers) {
            return new SynthesisEvidence(
                    evidenceFor(AgentType.MARKET_DATA, tickers),
                    evidenceFor(AgentType.FUNDAMENTALS, tickers),
                    evidenceFor(AgentType.NEWS, tickers),
                    evidenceFor(AgentType.TECHNICAL_ANALYSIS, tickers)
            );
        }

        private Map<String, Object> evidenceFor(AgentType agentType, List<String> tickers) {
            Map<String, Object> values = evidence.getOrDefault(agentType, Map.of());
            if (tickers == null || tickers.isEmpty()) {
                return values;
            }

            Map<String, Object> filtered = new LinkedHashMap<>();
            for (String ticker : tickers) {
                Object value = values.get(ticker);
                if (value != null) {
                    filtered.put(ticker, value);
                }
            }
            return filtered;
        }
    }
}
