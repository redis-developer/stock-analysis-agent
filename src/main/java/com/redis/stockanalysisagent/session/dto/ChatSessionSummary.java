package com.redis.stockanalysisagent.session.dto;

public record ChatSessionSummary(
        String sessionId,
        String createdAt,
        ChatSessionMetadata metadata
) {
    public ChatSessionSummary(String sessionId, String createdAt) {
        this(sessionId, createdAt, ChatSessionMetadata.empty());
    }

    public ChatSessionSummary {
        metadata = metadata == null ? ChatSessionMetadata.empty() : metadata;
    }
}
