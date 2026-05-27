package com.redis.stockanalysisagent.session.controller.vo;

import java.util.List;

public record ChatSessionsResponse(
        List<String> sessions
) {
}
