package com.redis.stockanalysisagent.semanticcache;

import java.util.Optional;

public interface SemanticAnalysisCache {

    Optional<String> findCachedResponse(String request);

    void storeFinalResponse(String request, String response);
}
