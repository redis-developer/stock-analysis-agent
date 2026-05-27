package com.redis.stockanalysisagent.ratelimiting;

public interface RateLimitService {

    RateLimitDecision consume(String key);

    RateLimitStatus status(String key);
}
