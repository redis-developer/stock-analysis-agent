package com.redis.stockanalysisagent.ratelimiting;

import com.redis.stockanalysisagent.session.ChatSessionAccess;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitStatusProviderTests {

    private final ChatSessionAccess sessionAccess = new ChatSessionAccess(true);
    private final RateLimitingProperties properties = new RateLimitingProperties();

    @Test
    void readsCurrentStatusForSessionUser() {
        MockHttpSession session = new MockHttpSession();
        sessionAccess.storeUserId(session, "alice");
        RateLimitStatusProvider provider = new RateLimitStatusProvider(
                sessionAccess,
                properties,
                serviceReturning(new RateLimitStatus(4, 6))
        );

        RateLimitStatus status = provider.status(session);

        assertThat(status.remainingTokens()).isEqualTo(4);
        assertThat(status.limit()).isEqualTo(6);
    }

    @Test
    void returnsDefaultStatusWhenSessionRateLimitingIsDisabled() {
        MockHttpSession session = new MockHttpSession();
        sessionAccess.storeUserId(session, "alice");
        sessionAccess.storeRateLimitingEnabled(session, false);
        RateLimitStatusProvider provider = new RateLimitStatusProvider(
                sessionAccess,
                properties,
                serviceReturning(new RateLimitStatus(4, 6))
        );

        RateLimitStatus status = provider.status(session);

        assertThat(status.remainingTokens()).isEqualTo(6);
        assertThat(status.limit()).isEqualTo(6);
    }

    private RateLimitService serviceReturning(RateLimitStatus status) {
        return new RateLimitService() {
            @Override
            public RateLimitDecision consume(String key) {
                return new RateLimitDecision(true, status.remainingTokens(), null);
            }

            @Override
            public RateLimitStatus status(String key) {
                return status;
            }
        };
    }
}
