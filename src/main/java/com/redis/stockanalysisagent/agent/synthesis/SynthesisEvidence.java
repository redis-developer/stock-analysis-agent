package com.redis.stockanalysisagent.agent.synthesis;

import java.util.LinkedHashMap;
import java.util.Map;

public record SynthesisEvidence(
        Map<String, Object> marketData,
        Map<String, Object> fundamentals,
        Map<String, Object> news,
        Map<String, Object> technicalAnalysis
) {

    public SynthesisEvidence {
        marketData = copyOf(marketData);
        fundamentals = copyOf(fundamentals);
        news = copyOf(news);
        technicalAnalysis = copyOf(technicalAnalysis);
    }

    public boolean isEmpty() {
        return marketData.isEmpty()
                && fundamentals.isEmpty()
                && news.isEmpty()
                && technicalAnalysis.isEmpty();
    }

    private static Map<String, Object> copyOf(Map<String, Object> values) {
        return values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }
}
