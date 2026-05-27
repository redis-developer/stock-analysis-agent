package com.redis.stockanalysisagent.semanticcache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

final class LangCacheApiModels {

    private LangCacheApiModels() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SearchRequest(
            @JsonProperty("prompt") String prompt,
            @JsonProperty("similarityThreshold") Double similarityThreshold,
            @JsonProperty("searchStrategies") List<String> searchStrategies,
            @JsonProperty("attributes") Map<String, String> attributes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(
            @JsonProperty("data") List<CacheEntry> data
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CacheEntry(
            @JsonProperty("id") String id,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("response") String response,
            @JsonProperty("attributes") Map<String, String> attributes,
            @JsonProperty("similarity") Double similarity,
            @JsonProperty("searchStrategy") String searchStrategy
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SetRequest(
            @JsonProperty("prompt") String prompt,
            @JsonProperty("response") String response,
            @JsonProperty("attributes") Map<String, String> attributes,
            @JsonProperty("ttlMillis") Long ttlMillis
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SetResponse(
            @JsonProperty("entryId") String entryId
    ) {
    }
}
