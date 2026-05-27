package com.redis.stockanalysisagent.providers;

import com.redis.stockanalysisagent.stock.NewsSnapshot;

public interface NewsProvider {

    NewsSnapshot fetchSnapshot(String ticker);
}
