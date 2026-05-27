package com.redis.stockanalysisagent.session.controller.vo;

import com.redis.stockanalysisagent.session.dto.ChatSessionMessage;

import java.util.List;

public record ChatSessionResponse(
        String userId,
        String sessionId,
        List<ChatSessionMessage> messages
) {
}
