package com.redis.stockanalysisagent.semanticguardrail;

import com.redis.stockanalysisagent.chat.WorkflowProgress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SemanticGuardrailConfig {

    @Bean
    public SemanticGuardrailAdvisor semanticGuardrailAdvisor(
            SemanticGuardrailService semanticGuardrailService,
            WorkflowProgress workflowProgress
    ) {
        return new SemanticGuardrailAdvisor(semanticGuardrailService, workflowProgress);
    }
}
