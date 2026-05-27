package com.redis.stockanalysisagent.providers;

import com.redis.stockanalysisagent.stock.TechnicalAnalysisSnapshot;

public interface TechnicalAnalysisProvider {

    TechnicalAnalysisSnapshot fetchSnapshot(String ticker);
}
