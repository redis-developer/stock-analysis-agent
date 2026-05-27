package com.redis.stockanalysisagent.semanticcache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stock-analysis.semantic-cache")
public class SemanticCacheProperties {

    private int ttlSeconds = 300;
    private final LangCache langCache = new LangCache();

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public LangCache getLangCache() {
        return langCache;
    }

    public static class LangCache {

        private String url;
        private String cacheId;
        private String apiKey;
        private Double similarityThreshold = 0.9;
        private boolean exactSearch = true;
        private boolean semanticSearch = true;
        private boolean includeKindAttribute = false;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getCacheId() {
            return cacheId;
        }

        public void setCacheId(String cacheId) {
            this.cacheId = cacheId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(Double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public boolean isExactSearch() {
            return exactSearch;
        }

        public void setExactSearch(boolean exactSearch) {
            this.exactSearch = exactSearch;
        }

        public boolean isSemanticSearch() {
            return semanticSearch;
        }

        public void setSemanticSearch(boolean semanticSearch) {
            this.semanticSearch = semanticSearch;
        }

        public boolean isIncludeKindAttribute() {
            return includeKindAttribute;
        }

        public void setIncludeKindAttribute(boolean includeKindAttribute) {
            this.includeKindAttribute = includeKindAttribute;
        }
    }
}
