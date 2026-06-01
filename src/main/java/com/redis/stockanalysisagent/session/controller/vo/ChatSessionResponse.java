package com.redis.stockanalysisagent.session.controller.vo;

import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;
import com.redis.stockanalysisagent.session.dto.ChatSessionMessage;

import java.util.List;

public record ChatSessionResponse(
        String userId,
        String sessionId,
        List<ChatSessionMessage> messages,
        ChatSessionMetadata metadata
) {
    public ChatSessionResponse(String userId, String sessionId, List<ChatSessionMessage> messages) {
        this(userId, sessionId, messages, ChatSessionMetadata.empty());
    }

    public ChatSessionResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
        metadata = metadata == null ? ChatSessionMetadata.empty() : metadata;
    }
}
