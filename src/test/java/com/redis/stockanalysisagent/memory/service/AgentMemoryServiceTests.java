package com.redis.stockanalysisagent.memory.service;

import com.redis.agentmemory.models.workingmemory.MemoryMessage;
import com.redis.stockanalysisagent.memory.AgentMemoryProperties;
import com.redis.stockanalysisagent.reliability.circuitbreaker.CircuitBreakerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                        .createdAt(Instant.parse("2026-05-26T10:00:00Z"))
                        .build()),
                "alice",
                null
        );

        assertThat(requests)
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.method()).isEqualTo(HttpMethod.POST);
                    assertThat(request.url().getPath()).isEqualTo("/v1/stores/test-store/session-memory/events");
                    assertThat(body(request))
                            .contains("\"createdAt\":\"2026-05-26T10:00:00Z\"");
                });
    }

    @Test
    void listSessionsReadsSessionsResponseField() {
        WebClient client = WebClient.builder()
                .baseUrl("https://memory.example.test")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "sessions": ["session-1"],
                                  "total": 1
                                }
                                """)
                        .build()))
                .build();
        AgentMemoryService service = new AgentMemoryService(client, properties());

        assertThat(service.listSessions(null)).containsExactly("session-1");
    }

    @Test
    void listSessionsStillReadsItemsResponseFieldReturnedByCurrentDataPlane() {
        WebClient client = WebClient.builder()
                .baseUrl("https://memory.example.test")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "items": ["session-1"],
                                  "total": 1
                                }
                                """)
                        .build()))
                .build();
        AgentMemoryService service = new AgentMemoryService(client, properties());

        assertThat(service.listSessions(null)).containsExactly("session-1");
    }

    @Test
    void missingSessionMemoryDoesNotReachCircuitBreakerAsFailure() {
        WebClient client = WebClient.builder()
                .baseUrl("https://memory.example.test")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build()))
                .build();
        CircuitBreakerService circuitBreakerService = mock(CircuitBreakerService.class);
        when(circuitBreakerService.call(eq("agent-memory"), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(1);
            try {
                return supplier.get();
            } catch (WebClientResponseException.NotFound ex) {
                throw new AssertionError("Missing session memory should not count as provider failure.", ex);
            }
        });
        AgentMemoryService service = new AgentMemoryService(client, properties(), circuitBreakerService);

        assertThat(service.listSessionEvents("missing-session", "alice")).isEmpty();
        verify(circuitBreakerService).call(eq("agent-memory"), any());
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

    private static String body(ClientRequest request) {
        MockClientHttpRequest httpRequest = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(httpRequest, ExchangeStrategies.withDefaults()).block();
        return httpRequest.getBodyAsString().block();
    }
}
