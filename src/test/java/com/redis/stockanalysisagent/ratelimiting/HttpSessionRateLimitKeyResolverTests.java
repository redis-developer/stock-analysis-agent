package com.redis.stockanalysisagent.ratelimiting;

import com.redis.stockanalysisagent.session.ChatSessionAccess;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSessionRateLimitKeyResolverTests {

    private final RateLimitingProperties properties = new RateLimitingProperties();
    private final ChatSessionAccess sessionAccess = new ChatSessionAccess(true);
    private final HttpSessionRateLimitKeyResolver resolver = new HttpSessionRateLimitKeyResolver(
            sessionAccess,
            properties
    );

    @Test
    void resolvesSessionUserWhenRateLimitingIsEnabled() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionAccess.storeUserId(request.getSession(true), "alice");

        Optional<String> key = resolver.resolve(request);

        assertThat(key).contains("stock-analysis:rate-limit:user:alice");
    }

    @Test
    void skipsSessionUserWhenRateLimitingIsDisabled() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionAccess.storeUserId(request.getSession(true), "alice");
        sessionAccess.storeRateLimitingEnabled(request.getSession(), false);

        Optional<String> key = resolver.resolve(request);

        assertThat(key).isEmpty();
    }
}
