package com.redis.stockanalysisagent.semanticcache;

import com.redis.stockanalysisagent.reliability.CircuitBreakerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LangCacheSemanticAnalysisCache implements SemanticAnalysisCache {

    private static final Logger log = LoggerFactory.getLogger(LangCacheSemanticAnalysisCache.class);
    private static final String CACHE_KIND = "stock-analysis";

    private final WebClient client;
    private final String cacheId;
    private final Double similarityThreshold;
    private final long ttlMillis;
    private final List<String> searchStrategies;
    private final Map<String, String> attributes;
    private final CircuitBreakerService circuitBreakerService;

    public LangCacheSemanticAnalysisCache(
            SemanticCacheProperties properties,
            CircuitBreakerService circuitBreakerService
    ) {
        SemanticCacheProperties.LangCache langCache = properties.getLangCache();
        this.client = WebClient.builder()
                .baseUrl(requireText(langCache.getUrl(), "stock-analysis.semantic-cache.lang-cache.url"))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer "
                        + requireText(langCache.getApiKey(), "stock-analysis.semantic-cache.lang-cache.api-key"))
                .build();
        this.cacheId = requireText(langCache.getCacheId(), "stock-analysis.semantic-cache.lang-cache.cache-id");
        this.similarityThreshold = langCache.getSimilarityThreshold();
        this.ttlMillis = Math.max(1, properties.getTtlSeconds()) * 1000L;
        this.searchStrategies = searchStrategies(langCache);
        this.attributes = langCache.isIncludeKindAttribute() ? Map.of("kind", CACHE_KIND) : null;
        this.circuitBreakerService = circuitBreakerService;
    }

    @Override
    public Optional<String> findCachedResponse(String request) {
        LangCacheApiModels.SearchRequest searchRequest = new LangCacheApiModels.SearchRequest(
                request,
                similarityThreshold,
                searchStrategies,
                attributes
        );

        try {
            LangCacheApiModels.SearchResponse response = circuitBreakerService.call("lang-cache", () ->
                    client.post()
                            .uri("/v1/caches/{cacheId}/entries/search", cacheId)
                            .bodyValue(searchRequest)
                            .retrieve()
                            .bodyToMono(LangCacheApiModels.SearchResponse.class)
                            .block());
            if (response == null || response.data() == null || response.data().isEmpty()) {
                return Optional.empty();
            }

            LangCacheApiModels.CacheEntry cacheHit = response.data().getFirst();
            if (cacheHit.response() == null || cacheHit.response().isBlank()) {
                return Optional.empty();
            }

            log.info("LangCache hit for request with similarity {}", cacheHit.similarity());
            return Optional.of(cacheHit.response());
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to search LangCache: " + errorSummary(e), e);
        }
    }

    @Override
    public void storeFinalResponse(String request, String response) {
        LangCacheApiModels.SetRequest setRequest = new LangCacheApiModels.SetRequest(
                request,
                response,
                attributes,
                ttlMillis
        );

        try {
            LangCacheApiModels.SetResponse setResponse = circuitBreakerService.call("lang-cache", () ->
                    client.post()
                            .uri("/v1/caches/{cacheId}/entries", cacheId)
                            .bodyValue(setRequest)
                            .retrieve()
                            .bodyToMono(LangCacheApiModels.SetResponse.class)
                            .block());
            log.info("LangCache stored response with entry id {}",
                    setResponse != null ? setResponse.entryId() : null);
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to store LangCache entry: " + errorSummary(e), e);
        }
    }

    private List<String> searchStrategies(SemanticCacheProperties.LangCache langCache) {
        List<String> strategies = new ArrayList<>();
        if (langCache.isExactSearch()) {
            strategies.add("exact");
        }
        if (langCache.isSemanticSearch()) {
            strategies.add("semantic");
        }
        if (strategies.isEmpty()) {
            throw new IllegalStateException(
                    "At least one LangCache search strategy must be enabled for semantic cache"
            );
        }
        return List.copyOf(strategies);
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
        return value.trim();
    }

    private String errorSummary(WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return e.getStatusCode().toString();
        }
        return e.getStatusCode() + " " + body;
    }
}
