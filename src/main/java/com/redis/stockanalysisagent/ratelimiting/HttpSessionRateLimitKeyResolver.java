package com.redis.stockanalysisagent.ratelimiting;

import com.redis.stockanalysisagent.session.ChatSessionAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "app.rate-limiting", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpSessionRateLimitKeyResolver implements RateLimitKeyResolver {

    private final ChatSessionAccess sessionAccess;
    private final RateLimitingProperties properties;

    public HttpSessionRateLimitKeyResolver(
            ChatSessionAccess sessionAccess,
            RateLimitingProperties properties
    ) {
        this.sessionAccess = sessionAccess;
        this.properties = properties;
    }

    @Override
    public Optional<String> resolve(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String userId = sessionAccess.sessionUserId(session);
        if (userId == null || !sessionAccess.sessionRateLimitingEnabled(session)) {
            return Optional.empty();
        }

        return Optional.of(properties.requireRedisKeyPrefix() + ":" + userId);
    }
}
