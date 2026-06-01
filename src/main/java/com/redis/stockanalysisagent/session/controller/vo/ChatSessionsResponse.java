package com.redis.stockanalysisagent.session.controller.vo;

import com.redis.stockanalysisagent.session.dto.ChatSessionSummary;

import java.util.List;

public record ChatSessionsResponse(
        List<String> sessions,
        List<ChatSessionSummary> sessionDetails
) {
    public ChatSessionsResponse(List<String> sessions) {
        this(sessions, List.of());
    }
}
