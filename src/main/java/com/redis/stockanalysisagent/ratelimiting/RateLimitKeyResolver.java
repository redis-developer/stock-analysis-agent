package com.redis.stockanalysisagent.ratelimiting;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

public interface RateLimitKeyResolver {

    Optional<String> resolve(HttpServletRequest request);
}
