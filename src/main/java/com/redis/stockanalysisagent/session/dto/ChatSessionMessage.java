package com.redis.stockanalysisagent.session.dto;

public record ChatSessionMessage(
        String role,
        String content
) {
}
