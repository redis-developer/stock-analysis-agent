package com.redis.stockanalysisagent.semanticcache;

import com.redis.stockanalysisagent.chat.WorkflowProgress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SemanticCacheConfig {

    @Bean
    public SemanticCacheAdvisor semanticCacheAdvisor(
            SemanticAnalysisCache semanticAnalysisCache,
            WorkflowProgress workflowProgress
    ) {
        return new SemanticCacheAdvisor(semanticAnalysisCache, workflowProgress);
    }

    @Bean
    public SemanticCacheStoreAdvisor semanticCacheStoreAdvisor(SemanticAnalysisCache semanticAnalysisCache) {
        return new SemanticCacheStoreAdvisor(semanticAnalysisCache);
    }
}
