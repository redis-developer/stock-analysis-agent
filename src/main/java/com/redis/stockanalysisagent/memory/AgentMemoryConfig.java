package com.redis.stockanalysisagent.memory;

import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.memory.service.AgentMemoryService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AgentMemoryConfig {

    @Bean
    public WebClient agentMemoryWebClient(AgentMemoryProperties properties) {
        String url = requireText(properties.getUrl(), "agent-memory.url");
        String apiKey = requireText(properties.getApiKey(), "agent-memory.api-key");
        return WebClient.builder()
                .baseUrl(url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Bean
    public AmsChatMemoryRepository amsChatMemoryRepository(
            AgentMemoryService agentMemoryService
    ) {
        return new AmsChatMemoryRepository(agentMemoryService);
    }

    @Bean
    public ChatMemory chatMemory(AmsChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public LongTermMemoryAdvisor longTermMemoryAdvisor(
            AgentMemoryService agentMemoryService,
            AmsChatMemoryRepository memoryRepository,
            ChatProgressPublisher progressPublisher
    ) {
        return new LongTermMemoryAdvisor(agentMemoryService, memoryRepository, progressPublisher, 5);
    }

    private String requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " is required");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
