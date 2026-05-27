package com.redis.stockanalysisagent.semanticguardrail;

import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SemanticGuardrailConfig {

    @Bean
    public SemanticGuardrailAdvisor semanticGuardrailAdvisor(
            SemanticGuardrailService semanticGuardrailService,
            ChatProgressPublisher progressPublisher
    ) {
        return new SemanticGuardrailAdvisor(semanticGuardrailService, progressPublisher);
    }
}
