package com.redis.stockanalysisagent.providers;

import com.redis.stockanalysisagent.stock.FundamentalsSnapshot;
import com.redis.stockanalysisagent.stock.MarketSnapshot;

import java.util.Optional;

public interface FundamentalsProvider {

    FundamentalsSnapshot fetchSnapshot(String ticker, Optional<MarketSnapshot> marketSnapshot);
}
