package com.redis.stockanalysisagent.agent.technicalanalysis;

import com.redis.stockanalysisagent.providers.TechnicalAnalysisProvider;
import com.redis.stockanalysisagent.stock.TechnicalAnalysisSnapshot;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TechnicalAnalysisTools {

    private final TechnicalAnalysisProvider technicalAnalysisProvider;

    public TechnicalAnalysisTools(TechnicalAnalysisProvider technicalAnalysisProvider) {
        this.technicalAnalysisProvider = technicalAnalysisProvider;
    }

    @Tool(description = "Fetch a normalized technical-analysis snapshot for a stock ticker, including latest close, SMA, EMA, RSI, trend signal, momentum signal, and source.")
    public TechnicalAnalysisSnapshot getTechnicalAnalysisSnapshot(
            @ToolParam(description = "The stock ticker symbol in uppercase, for example AAPL.")
            String ticker
    ) {
        return technicalAnalysisProvider.fetchSnapshot(ticker);
    }
}
