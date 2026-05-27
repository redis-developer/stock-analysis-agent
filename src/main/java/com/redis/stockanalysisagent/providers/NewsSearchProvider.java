package com.redis.stockanalysisagent.providers;

import com.redis.stockanalysisagent.stock.NewsItem;

import java.util.List;

public interface NewsSearchProvider {

    SearchResult search(String ticker, String companyName, String question);

    record SearchResult(List<NewsItem> items, String answer) {

        public static SearchResult empty() {
            return new SearchResult(List.of(), null);
        }
    }
}
