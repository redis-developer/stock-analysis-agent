package com.redis.stockanalysisagent.memory.controller.vo;

import java.util.List;

public record LongTermMemoriesResponse(
        List<LongTermMemoryResponse> memories
) {
}
