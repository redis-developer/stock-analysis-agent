package com.redis.stockanalysisagent.stock;

import java.util.List;

public record NewsSnapshot(
        String ticker,
        String companyName,
        List<NewsItem> officialItems,
        List<NewsItem> webItems,
        String webSummary,
        String source
) {
}
