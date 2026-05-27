package com.redis.stockanalysisagent.providers;

import com.redis.stockanalysisagent.stock.MarketSnapshot;

public interface MarketDataProvider {

    MarketSnapshot fetchSnapshot(String ticker);
}
