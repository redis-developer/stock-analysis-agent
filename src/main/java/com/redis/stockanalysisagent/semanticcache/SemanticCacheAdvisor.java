package com.redis.stockanalysisagent.semanticcache;

import com.redis.stockanalysisagent.chat.WorkflowProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * Semantic cache lookup advisor.
 */
public class SemanticCacheAdvisor implements CallAdvisor {

    public static final String CACHE_HIT = SemanticCacheSupport.CACHE_HIT;
    public static final String BYPASS_CACHE = SemanticCacheSupport.CACHE_BYPASS;
    public static final String CACHE_KEY = SemanticCacheSupport.CACHE_KEY;
    public static final String CACHE_DURATION_MS = SemanticCacheSupport.CACHE_DURATION_MS;
    private static final Logger log = LoggerFactory.getLogger(SemanticCacheAdvisor.class);
    private static final int DEFAULT_ORDER = 20;

    private final SemanticAnalysisCache semanticCache;
    private final WorkflowProgress workflowProgress;

    public SemanticCacheAdvisor(SemanticAnalysisCache semanticCache, WorkflowProgress workflowProgress) {
        this.semanticCache = semanticCache;
        this.workflowProgress = workflowProgress;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (SemanticCacheSupport.shouldBypass(request)) {
            workflowProgress.completed(
                    "SEMANTIC_CACHE",
                    "Semantic cache",
                    WorkflowProgress.KIND_SYSTEM,
                    0,
                    "Skipped semantic cache for this request."
            );
            return chain.nextCall(request);
        }

        String cacheKey = SemanticCacheSupport.resolveCacheKey(request);
        if (cacheKey == null) {
            return chain.nextCall(request);
        }

        long startedAt = System.nanoTime();
        workflowProgress.running(
                "SEMANTIC_CACHE",
                "Semantic cache",
                WorkflowProgress.KIND_SYSTEM,
                "Checking Redis semantic cache."
        );
        try {
            var cachedResponse = semanticCache.findCachedResponse(cacheKey);
            long durationMs = elapsedDurationMs(startedAt);
            if (cachedResponse.isPresent()) {
                workflowProgress.completed(
                        "SEMANTIC_CACHE",
                        "Semantic cache",
                        WorkflowProgress.KIND_SYSTEM,
                        durationMs,
                        "Found a reusable response in the semantic cache."
                );
                return SemanticCacheSupport.asCacheHitResponse(cacheKey, cachedResponse.get(), request.context(), durationMs);
            }
        } catch (RuntimeException ex) {
            log.warn("Skipping semantic cache lookup because retrieval failed.", ex);
            workflowProgress.failed(
                    "SEMANTIC_CACHE",
                    "Semantic cache",
                    WorkflowProgress.KIND_SYSTEM,
                    elapsedDurationMs(startedAt),
                    errorMessage(ex)
            );
        }

        long durationMs = elapsedDurationMs(startedAt);
        workflowProgress.completed(
                "SEMANTIC_CACHE",
                "Semantic cache",
                WorkflowProgress.KIND_SYSTEM,
                durationMs,
                "No reusable response found in the semantic cache."
        );
        ChatClientResponse response = chain.nextCall(request);
        return response == null ? null : SemanticCacheSupport.markMiss(response, cacheKey, durationMs);
    }

    private long elapsedDurationMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
