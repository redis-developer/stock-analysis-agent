package com.redis.stockanalysisagent.stock;

import java.time.LocalDate;

public record NewsItem(
        LocalDate publishedAt,
        String publisher,
        String label,
        String title,
        String summary,
        String url
) {
}
