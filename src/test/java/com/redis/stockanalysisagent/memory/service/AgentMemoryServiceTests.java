package com.redis.stockanalysisagent.memory.service;

import com.redis.agentmemory.models.workingmemory.MemoryMessage;
import com.redis.stockanalysisagent.memory.AgentMemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryServiceTests {

    @Test
    void appendingUserMessagesDoesNotCreateLongTermMemoryRecords() {
        List<ClientRequest> requests = new ArrayList<>();
        WebClient client = WebClient.builder()
                .baseUrl("https://memory.example.test")
                .exchangeFunction(request -> {
                    requests.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("""
                                    {
                                      "event": {
                                        "eventId": "event-1",
                                        "actorId": "alice",
                                        "sessionId": "session-1",
                                        "role": "USER",
                                        "content": [{"text": "hello"}],
                                        "createdAt": "2026-05-26T10:00:00Z",
                                        "metadata": {"namespace": "stock-analysis"}
                                      }
                                    }
                                    """)
                            .build());
                })
                .build();
        AgentMemoryService service = new AgentMemoryService(client, properties());

        service.appendMessagesToWorkingMemory(
                "session-1",
                List.of(MemoryMessage.builder()
                        .role("user")
                        .content("hello")
                        .build()),
                "alice",
                null
        );

        assertThat(requests)
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.method()).isEqualTo(HttpMethod.POST);
                    assertThat(request.url().getPath()).isEqualTo("/v1/stores/test-store/session-memory/events");
                });
    }

    @Test
    void createLongTermMemoryUsesRamBulkCreateEndpoint() {
        List<ClientRequest> requests = new ArrayList<>();
        WebClient client = WebClient.builder()
                .baseUrl("https://memory.example.test")
                .exchangeFunction(request -> {
                    requests.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("""
                                    {
                                      "created": ["memory-1"],
                                      "errors": []
                                    }
                                    """)
                            .build());
                })
                .build();
        AgentMemoryService service = new AgentMemoryService(client, properties());

        String memoryId = service.createLongTermMemory(
                "alice",
                "session-1",
                "Alice owns NVDA.",
                "semantic",
                List.of("stocks")
        );

        assertThat(memoryId).isNotBlank();
        assertThat(memoryId).isEqualTo("memory-1");
        assertThat(requests)
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.method()).isEqualTo(HttpMethod.POST);
                    assertThat(request.url().getPath()).isEqualTo("/v1/stores/test-store/long-term-memory");
                });
    }

    private static AgentMemoryProperties properties() {
        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.setStoreId("test-store");
        properties.setApiKey("test-api-key");
        properties.setNamespace("stock-analysis");
        return properties;
    }
}
