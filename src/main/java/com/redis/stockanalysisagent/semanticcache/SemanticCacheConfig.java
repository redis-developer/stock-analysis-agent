package com.redis.stockanalysisagent.semanticcache;

import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SemanticCacheConfig {

    @Bean
    public SemanticCacheAdvisor semanticCacheAdvisor(
            SemanticAnalysisCache semanticAnalysisCache,
            ChatProgressPublisher progressPublisher
    ) {
        return new SemanticCacheAdvisor(semanticAnalysisCache, progressPublisher);
    }

    @Bean
    public SemanticCacheStoreAdvisor semanticCacheStoreAdvisor(SemanticAnalysisCache semanticAnalysisCache) {
        return new SemanticCacheStoreAdvisor(semanticAnalysisCache);
    }
}
