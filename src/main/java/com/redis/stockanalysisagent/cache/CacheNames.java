package com.redis.stockanalysisagent.cache;

import java.util.List;

public final class CacheNames {

    public static final String MARKET_DATA_QUOTES = "market-data-quotes";
    public static final String TECHNICAL_ANALYSIS_SNAPSHOTS = "technical-analysis-snapshots";
    public static final String HISTORICAL_CANDLES = "historical-candles";
    public static final String SEC_TICKER_INDEX = "sec-ticker-index";
    public static final String SEC_COMPANY_FACTS = "sec-company-facts";
    public static final String SEC_SUBMISSIONS = "sec-submissions";
    public static final String TAVILY_NEWS_SEARCH = "tavily-news-search";

    public static List<String> externalDataCacheNames() {
        return List.of(
                MARKET_DATA_QUOTES,
                TECHNICAL_ANALYSIS_SNAPSHOTS,
                SEC_TICKER_INDEX,
                SEC_COMPANY_FACTS,
                SEC_SUBMISSIONS,
                TAVILY_NEWS_SEARCH
        );
    }

    private CacheNames() {
    }
}
