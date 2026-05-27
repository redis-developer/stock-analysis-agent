package com.redis.stockanalysisagent.memory.service;

import com.redis.agentmemory.models.longtermemory.MemoryRecordResult;
import com.redis.agentmemory.models.longtermemory.MemoryRecordResults;
import com.redis.agentmemory.models.workingmemory.MemoryMessage;
import com.redis.agentmemory.models.workingmemory.WorkingMemory;
import com.redis.agentmemory.models.workingmemory.WorkingMemoryResponse;
import com.redis.stockanalysisagent.memory.AgentMemoryProperties;
import com.redis.stockanalysisagent.memory.service.AgentMemoryApiModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentMemoryService {

    private static final String ASSISTANT_ACTOR_ID = "stock-analysis-agent";
    private static final String SYSTEM_ACTOR_ID = "system";
    private static final int MAX_SEARCH_LIMIT = 100;
    private static final int LIST_SESSIONS_PAGE_SIZE = 1000;
    private static final int LONG_TERM_MEMORY_PAGE_SIZE = 100;
    private static final Pattern RAM_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9-]+$");

    private final WebClient client;
    private final String storeId;
    private final String namespace;
    private final Double similarityThreshold;

    public AgentMemoryService(
            @Qualifier("agentMemoryWebClient") WebClient client,
            AgentMemoryProperties properties
    ) {
        this.client = client;
        this.storeId = requireRamIdentifier(properties.getStoreId(), "agent-memory.store-id");
        this.namespace = requireRamIdentifier(properties.getNamespace(), "agent-memory.namespace");
        this.similarityThreshold = properties.getSimilarityThreshold();
    }

    public WorkingMemoryResponse getWorkingMemory(String sessionId, String userId, String modelName) {
        try {
            SessionMemory sessionMemory = fetchSessionMemory(sessionId);
            if (!isOwnedBy(sessionMemory, userId)) {
                return emptyWorkingMemory(sessionId, userId);
            }
            return toWorkingMemoryResponse(sessionMemory);
        } catch (WebClientResponseException.NotFound ignored) {
            return emptyWorkingMemory(sessionId, userId);
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to get Agent Memory Server session memory", e);
        }
    }

    public List<String> listSessions(String userId) {
        try {
            List<String> sessions = new ArrayList<>();
            String pageToken = null;
            do {
                String currentPageToken = pageToken;
                ListSessionsResponse response = client.get()
                        .uri(uriBuilder -> {
                            var builder = uriBuilder
                                    .path("/v1/stores/{storeId}/session-memory")
                                    .queryParam("limit", LIST_SESSIONS_PAGE_SIZE);
                            if (hasText(currentPageToken)) {
                                builder.queryParam("pageToken", currentPageToken);
                            }
                            return builder.build(storeId);
                        })
                        .retrieve()
                        .bodyToMono(ListSessionsResponse.class)
                        .block();
                if (response == null) {
                    return sessions;
                }
                if (response.items() != null) {
                    for (String sessionId : response.items()) {
                        if (!hasText(userId) || sessionBelongsToUser(sessionId, userId)) {
                            sessions.add(sessionId);
                        }
                    }
                }
                pageToken = response.nextPageToken();
            } while (hasText(pageToken));
            return sessions;
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to list Agent Memory Server session memories", e);
        }
    }

    public void appendMessagesToWorkingMemory(
            String sessionId,
            List<MemoryMessage> messages,
            String userId,
            String modelName
    ) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        for (MemoryMessage message : messages) {
            addSessionEvent(sessionId, message, userId);
        }
    }


    public void putWorkingMemory(
            String sessionId,
            WorkingMemory memory,
            String userId,
            String modelName
    ) {
        // Agent Memory Server exposes append and delete operations for session memory, not full replacement.
    }


    public void deleteWorkingMemory(String sessionId, String userId) {
        if (hasText(userId) && !sessionBelongsToUser(sessionId, userId)) {
            return;
        }

        try {
            client.delete()
                    .uri("/v1/stores/{storeId}/session-memory/{sessionId}", storeId, sessionId)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException.NotFound ignored) {
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to delete Agent Memory Server session memory", e);
        }
    }


    public List<MemoryRecordResult> listLongTermMemories(String userId) {
        LongTermMemoryFilter filter = longTermMemoryOwnerFilter(userId);

        try {
            List<MemoryRecordResult> memories = new ArrayList<>();
            String pageToken = null;
            do {
                SearchLongTermMemoryRequest request = new SearchLongTermMemoryRequest(
                        null,
                        null,
                        filter,
                        FilterConjunction.ALL,
                        LONG_TERM_MEMORY_PAGE_SIZE,
                        pageToken
                );
                SearchLongTermMemoryResponse response = client.post()
                        .uri("/v1/stores/{storeId}/long-term-memory/search", storeId)
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(SearchLongTermMemoryResponse.class)
                        .block();
                if (response == null) {
                    break;
                }
                if (response.items() != null) {
                    response.items().stream()
                            .map(this::toMemoryRecordResult)
                            .forEach(memories::add);
                }
                pageToken = response.nextPageToken();
            } while (hasText(pageToken));
            return memories;
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to list Agent Memory Server long term memories", e);
        }
    }


    public String createLongTermMemory(
            String userId,
            String sessionId,
            String text,
            String memoryType,
            List<String> topics
    ) {
        String normalizedText = requireText(text, "memory text");
        String memoryId = UUID.randomUUID().toString();
        CreateMemoryRecord memory = new CreateMemoryRecord(
                memoryId,
                normalizedText,
                resolveMemoryType(memoryType),
                normalizeText(sessionId),
                requireText(userId, "user id"),
                namespace,
                normalizeTopics(topics)
        );

        try {
            BulkCreateLongTermMemoriesResponse response = client.post()
                    .uri("/v1/stores/{storeId}/long-term-memory", storeId)
                    .bodyValue(new BulkCreateLongTermMemoriesRequest(List.of(memory)))
                    .retrieve()
                    .bodyToMono(BulkCreateLongTermMemoriesResponse.class)
                    .block();
            if (response != null && response.errors() != null && !response.errors().isEmpty()) {
                throw new RuntimeException("Agent Memory Server long term memory create failed: " + response.errors());
            }
            if (response == null || response.created() == null || response.created().isEmpty()) {
                throw new RuntimeException("Agent Memory Server long term memory create did not return a created memory id.");
            }
            return response.created().getFirst();
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to create Agent Memory Server long term memory", e);
        }
    }


    public MemoryRecordResults searchLongTermMemory(String text, String userId, int limit) {
        int requestedLimit = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        LongTermMemoryFilter filter = longTermMemoryOwnerFilter(userId);
        SearchLongTermMemoryRequest request = new SearchLongTermMemoryRequest(
                text,
                similarityThreshold,
                filter,
                FilterConjunction.ALL,
                requestedLimit,
                null
        );

        try {
            List<MemoryRecordResult> memories = new ArrayList<>();
            SearchLongTermMemoryRequest pageRequest = request;
            String pageToken = null;
            do {
                SearchLongTermMemoryResponse response = client.post()
                        .uri("/v1/stores/{storeId}/long-term-memory/search", storeId)
                        .bodyValue(pageRequest)
                        .retrieve()
                        .bodyToMono(SearchLongTermMemoryResponse.class)
                        .block();
                if (response == null) {
                    break;
                }
                if (response.items() != null) {
                    response.items().stream()
                            .map(this::toMemoryRecordResult)
                            .limit(requestedLimit - memories.size())
                            .forEach(memories::add);
                }
                pageToken = response.nextPageToken();
                pageRequest = new SearchLongTermMemoryRequest(
                        text,
                        similarityThreshold,
                        filter,
                        FilterConjunction.ALL,
                        requestedLimit - memories.size(),
                        pageToken
                );
            } while (memories.size() < requestedLimit && hasText(pageToken));
            return new MemoryRecordResults(memories, memories.size());
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to search Agent Memory Server long term memory", e);
        }
    }


    public boolean deleteLongTermMemory(String userId, String memoryId) {
        if (!hasText(memoryId)) {
            return false;
        }

        boolean owned = listLongTermMemories(userId).stream()
                .anyMatch(memory -> memoryId.equals(memory.getId()));
        if (!owned) {
            return false;
        }

        return deleteLongTermMemoryIds(List.of(memoryId)) > 0;
    }


    public int deleteLongTermMemories(String userId) {
        List<String> memoryIds = listLongTermMemories(userId).stream()
                .map(MemoryRecordResult::getId)
                .filter(AgentMemoryService::hasText)
                .distinct()
                .toList();
        return deleteLongTermMemoryIds(memoryIds);
    }


    public String namespace() {
        return namespace;
    }

    private SessionMemory fetchSessionMemory(String sessionId) {
        return client.get()
                .uri("/v1/stores/{storeId}/session-memory/{sessionId}", storeId, sessionId)
                .retrieve()
                .bodyToMono(SessionMemory.class)
                .block();
    }

    private LongTermMemoryFilter longTermMemoryOwnerFilter(String userId) {
        return new LongTermMemoryFilter(
                null,
                hasText(userId) ? TagFilter.eq(userId) : null,
                null,
                null,
                null,
                null
        );
    }

    private MemoryType resolveMemoryType(String memoryType) {
        if (!hasText(memoryType)) {
            return MemoryType.SEMANTIC;
        }

        return MemoryType.fromValue(memoryType.trim());
    }

    private List<String> normalizeTopics(List<String> topics) {
        if (topics == null || topics.isEmpty()) {
            return List.of();
        }

        return topics.stream()
                .map(AgentMemoryService::normalizeText)
                .filter(AgentMemoryService::hasText)
                .distinct()
                .toList();
    }

    private static String normalizeText(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean sessionBelongsToUser(String sessionId, String userId) {
        if (!hasText(sessionId)) {
            return false;
        }

        try {
            return isOwnedBy(fetchSessionMemory(sessionId), userId);
        } catch (WebClientResponseException.NotFound ignored) {
            return false;
        }
    }

    private boolean isOwnedBy(SessionMemory sessionMemory, String userId) {
        if (sessionMemory == null) {
            return false;
        }

        if (!hasText(userId)) {
            return true;
        }

        if (hasText(sessionMemory.ownerId())) {
            return userId.equals(sessionMemory.ownerId());
        }

        return sessionMemory.events() != null && sessionMemory.events().stream()
                .anyMatch(event -> event != null
                        && event.role() == MessageRole.USER
                        && userId.equals(event.actorId()));
    }

    private SessionEvent addSessionEvent(String sessionId, MemoryMessage message, String userId) {
        AddSessionEventRequest request = new AddSessionEventRequest(
                sessionId,
                actorId(message, userId),
                toAgentMemoryRole(message.getRole()),
                List.of(ContentPart.text(message.getContent())),
                createdAt(message),
                Map.of("namespace", namespace)
        );

        try {
            AddSessionEventResponse response = client.post()
                    .uri("/v1/stores/{storeId}/session-memory/events", storeId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(AddSessionEventResponse.class)
                    .block();
            if (response == null || response.event() == null) {
                throw new RuntimeException("Agent Memory Server session event response did not include an event");
            }
            return response.event();
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to append Agent Memory Server session event", e);
        }
    }

    private int deleteLongTermMemoryIds(List<String> memoryIds) {
        int deleted = 0;
        for (int start = 0; start < memoryIds.size(); start += LONG_TERM_MEMORY_PAGE_SIZE) {
            int end = Math.min(memoryIds.size(), start + LONG_TERM_MEMORY_PAGE_SIZE);
            List<String> batch = memoryIds.subList(start, end);
            if (batch.isEmpty()) {
                continue;
            }

            try {
                BulkDeleteLongTermMemoriesResponse response = client.method(HttpMethod.DELETE)
                        .uri("/v1/stores/{storeId}/long-term-memory", storeId)
                        .bodyValue(new BulkDeleteLongTermMemoriesRequest(batch))
                        .retrieve()
                        .bodyToMono(BulkDeleteLongTermMemoriesResponse.class)
                        .block();
                if (response != null && response.errors() != null && !response.errors().isEmpty()) {
                    throw new RuntimeException("Agent Memory Server long term memory delete failed: " + response.errors());
                }
                deleted += response != null && response.deleted() != null ? response.deleted().size() : batch.size();
            } catch (WebClientResponseException e) {
                throw new RuntimeException("Failed to delete Agent Memory Server long term memories", e);
            }
        }
        return deleted;
    }

    private WorkingMemoryResponse toWorkingMemoryResponse(SessionMemory sessionMemory) {
        if (sessionMemory == null) {
            return null;
        }
        WorkingMemoryResponse response = new WorkingMemoryResponse();
        response.setSessionId(sessionMemory.sessionId());
        response.setUserId(sessionMemory.ownerId());
        response.setNamespace(namespace);
        response.setMessages(sessionMemory.events() != null
                ? sessionMemory.events().stream().map(this::toMemoryMessage).toList()
                : List.of());
        response.setMemories(List.of());
        response.setLastAccessed(Instant.now());
        return response;
    }

    private WorkingMemoryResponse emptyWorkingMemory(String sessionId, String userId) {
        WorkingMemoryResponse response = new WorkingMemoryResponse();
        response.setSessionId(sessionId);
        response.setUserId(userId);
        response.setNamespace(namespace);
        response.setMessages(new ArrayList<>());
        response.setMemories(new ArrayList<>());
        response.setLastAccessed(Instant.now());
        return response;
    }

    private MemoryMessage toMemoryMessage(SessionEvent event) {
        return MemoryMessage.builder()
                .id(event.eventId())
                .role(toSpringAiRole(event.role()))
                .content(contentText(event.content()))
                .createdAt(parseInstant(event.createdAt()))
                .build();
    }

    private MemoryRecordResult toMemoryRecordResult(LongTermMemoryRecord record) {
        MemoryRecordResult result = new MemoryRecordResult();
        result.setId(record.id());
        result.setText(record.text());
        result.setMemoryType(toOssMemoryType(record.memoryType()));
        result.setSessionId(record.sessionId());
        result.setUserId(record.ownerId());
        result.setNamespace(record.namespace());
        result.setTopics(record.topics());
        result.setCreatedAt(parseInstant(record.createdAt()));
        result.setUpdatedAt(parseInstant(record.updatedAt()));
        result.setLastAccessed(Instant.now());
        return result;
    }

    private com.redis.agentmemory.models.longtermemory.MemoryType toOssMemoryType(MemoryType memoryType) {
        if (memoryType == null) {
            return com.redis.agentmemory.models.longtermemory.MemoryType.MESSAGE;
        }
        return com.redis.agentmemory.models.longtermemory.MemoryType.fromValue(memoryType.getValue());
    }

    private String actorId(MemoryMessage message, String userId) {
        return switch (message.getRole()) {
            case "assistant" -> ASSISTANT_ACTOR_ID;
            case "system" -> SYSTEM_ACTOR_ID;
            default -> hasText(userId) ? userId : ASSISTANT_ACTOR_ID;
        };
    }

    private MessageRole toAgentMemoryRole(String role) {
        return switch (role) {
            case "assistant" -> MessageRole.ASSISTANT;
            case "system" -> MessageRole.SYSTEM;
            default -> MessageRole.USER;
        };
    }

    private String toSpringAiRole(MessageRole role) {
        if (role == null) {
            return "user";
        }
        return switch (role) {
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case USER -> "user";
        };
    }

    private String contentText(List<ContentPart> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.stream()
                .map(ContentPart::text)
                .filter(AgentMemoryService::hasText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String createdAt(MemoryMessage message) {
        return message.getCreatedAt() != null
                ? message.getCreatedAt().toString()
                : Instant.now().toString();
    }

    private Instant parseInstant(String value) {
        return hasText(value) ? OffsetDateTime.parse(value).toInstant() : Instant.now();
    }

    private static String requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " is required");
        }
        return value.trim();
    }

    private static String requireRamIdentifier(String value, String propertyName) {
        String resolved = requireText(value, propertyName);
        if (resolved.length() > 64 || !RAM_IDENTIFIER.matcher(resolved).matches()) {
            throw new IllegalStateException(propertyName + " must be 1-64 alphanumeric or dash characters");
        }
        return resolved;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
