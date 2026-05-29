package com.redis.stockanalysisagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "agent-memory.store-id=test-store",
        "agent-memory.api-key=test-api-key",
        "stock-analysis.semantic-cache.lang-cache.cache-id=test-cache",
        "stock-analysis.semantic-cache.lang-cache.api-key=test-api-key"
})
class StockAnalysisAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
