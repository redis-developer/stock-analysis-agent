package com.redis.stockanalysisagent.ratelimiting;

import com.redis.stockanalysisagent.session.ChatSessionAccess;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.rate-limiting", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitStatusProvider {

    private final ChatSessionAccess sessionAccess;
    private final RateLimitingProperties properties;
    private final RateLimitService rateLimitService;

    public RateLimitStatusProvider(
            ChatSessionAccess sessionAccess,
            RateLimitingProperties properties,
            RateLimitService rateLimitService
    ) {
        this.sessionAccess = sessionAccess;
        this.properties = properties;
        this.rateLimitService = rateLimitService;
    }

    public RateLimitStatus status(HttpSession session) {
        String userId = sessionAccess.sessionUserId(session);
        if (userId == null || !sessionAccess.sessionRateLimitingEnabled(session)) {
            return defaultStatus();
        }

        return rateLimitService.status(properties.requireRedisKeyPrefix() + ":" + userId);
    }

    public RateLimitStatus defaultStatus() {
        long limit = properties.requireRequestsPerMinute();
        return new RateLimitStatus(limit, limit);
    }
}
