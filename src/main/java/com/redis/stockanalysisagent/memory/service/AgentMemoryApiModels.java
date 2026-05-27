package com.redis.stockanalysisagent.memory.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Map;

public final class AgentMemoryApiModels {

    private AgentMemoryApiModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthResponse(
            @JsonProperty("status") String status
    ) {
    }

    public enum MessageRole {
        @JsonProperty("USER")
        USER("USER"),
        @JsonProperty("ASSISTANT")
        ASSISTANT("ASSISTANT"),
        @JsonProperty("SYSTEM")
        SYSTEM("SYSTEM");

        private final String value;

        MessageRole(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static MessageRole fromValue(String value) {
            for (MessageRole role : values()) {
                if (role.value.equalsIgnoreCase(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("Unknown message role: " + value);
        }
    }

    public enum MemoryType {
        @JsonProperty("semantic")
        SEMANTIC("semantic"),
        @JsonProperty("episodic")
        EPISODIC("episodic"),
        @JsonProperty("message")
        MESSAGE("message");

        private final String value;

        MemoryType(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static MemoryType fromValue(String value) {
            for (MemoryType type : values()) {
                if (type.value.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown memory type: " + value);
        }
    }

    public enum FilterConjunction {
        @JsonProperty("all")
        ALL("all"),
        @JsonProperty("any")
        ANY("any");

        private final String value;

        FilterConjunction(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static FilterConjunction fromValue(String value) {
            for (FilterConjunction conjunction : values()) {
                if (conjunction.value.equalsIgnoreCase(value)) {
                    return conjunction;
                }
            }
            throw new IllegalArgumentException("Unknown filter conjunction: " + value);
        }
    }

    public record ContentPart(
            @JsonProperty("text") String text
    ) {
        public static ContentPart text(String value) {
            return new ContentPart(value);
        }
    }

    public record AddSessionEventRequest(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("actorId") String actorId,
            @JsonProperty("role") MessageRole role,
            @JsonProperty("content") List<ContentPart> content,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("metadata") Map<String, Object> metadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddSessionEventResponse(
            @JsonProperty("event") SessionEvent event
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("actorId") String actorId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("role") MessageRole role,
            @JsonProperty("content") List<ContentPart> content,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("systemTimestamp") String systemTimestamp,
            @JsonProperty("metadata") Map<String, Object> metadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionMemory(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("ownerId") String ownerId,
            @JsonProperty("events") List<SessionEvent> events
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GetSessionEventResponse(
            @JsonProperty("event") SessionEvent event
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListSessionsResponse(
            @JsonProperty("items") List<String> items,
            @JsonProperty("total") int total,
            @JsonProperty("nextPageToken") String nextPageToken
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BulkOperationError(
            @JsonProperty("id") String id,
            @JsonProperty("error") String error
    ) {
    }

    public record CreateMemoryRecord(
            @JsonProperty("id") String id,
            @JsonProperty("text") String text,
            @JsonProperty("memoryType") MemoryType memoryType,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("ownerId") String ownerId,
            @JsonProperty("namespace") String namespace,
            @JsonProperty("topics") List<String> topics
    ) {
    }

    public record BulkCreateLongTermMemoriesRequest(
            @JsonProperty("memories") List<CreateMemoryRecord> memories
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BulkCreateLongTermMemoriesResponse(
            @JsonProperty("created") List<String> created,
            @JsonProperty("errors") List<BulkOperationError> errors
    ) {
    }

    public record BulkDeleteLongTermMemoriesRequest(
            @JsonProperty("memoryIds") List<String> memoryIds
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BulkDeleteLongTermMemoriesResponse(
            @JsonProperty("deleted") List<String> deleted,
            @JsonProperty("errors") List<BulkOperationError> errors
    ) {
    }

    public record TagFilter(
            @JsonProperty("eq") String eq,
            @JsonProperty("ne") String ne,
            @JsonProperty("in") List<String> in,
            @JsonProperty("all") List<String> all
    ) {
        public static TagFilter eq(String value) {
            return new TagFilter(value, null, null, null);
        }
    }

    public record DateTimeFilter(
            @JsonProperty("gt") String gt,
            @JsonProperty("lt") String lt,
            @JsonProperty("gte") String gte,
            @JsonProperty("lte") String lte,
            @JsonProperty("eq") String eq
    ) {
    }

    public record LongTermMemoryFilter(
            @JsonProperty("sessionId") TagFilter sessionId,
            @JsonProperty("ownerId") TagFilter ownerId,
            @JsonProperty("namespace") TagFilter namespace,
            @JsonProperty("topics") TagFilter topics,
            @JsonProperty("memoryType") TagFilter memoryType,
            @JsonProperty("createdAt") DateTimeFilter createdAt
    ) {
    }

    public record SearchLongTermMemoryRequest(
            @JsonProperty("text") String text,
            @JsonProperty("similarityThreshold") Double similarityThreshold,
            @JsonProperty("filter") LongTermMemoryFilter filter,
            @JsonProperty("filterOp") FilterConjunction filterOp,
            @JsonProperty("limit") Integer limit,
            @JsonProperty("pageToken") String pageToken
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LongTermMemoryRecord(
            @JsonProperty("id") String id,
            @JsonProperty("text") String text,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("updatedAt") String updatedAt,
            @JsonProperty("memoryType") MemoryType memoryType,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("ownerId") String ownerId,
            @JsonProperty("namespace") String namespace,
            @JsonProperty("topics") List<String> topics
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchLongTermMemoryResponse(
            @JsonProperty("items") List<LongTermMemoryRecord> items,
            @JsonProperty("nextPageToken") String nextPageToken
    ) {
    }

    public record UpdateLongTermMemoryRequest(
            @JsonProperty("text") String text,
            @JsonProperty("memoryType") MemoryType memoryType,
            @JsonProperty("topics") List<String> topics,
            @JsonProperty("namespace") String namespace,
            @JsonProperty("ownerId") String ownerId,
            @JsonProperty("sessionId") String sessionId
    ) {
    }
}
