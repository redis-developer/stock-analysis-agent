package com.redis.stockanalysisagent.ratelimiting;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTests {

    private final RateLimitingProperties properties = new RateLimitingProperties();

    @Test
    void allowsRequestWhenUserIsUnderLimit() throws Exception {
        RateLimitingFilter filter = filter(new RateLimitDecision(true, 5, Duration.ZERO), Optional.of("user:alice"));
        MockHttpServletRequest request = chatRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (ServletRequest ignoredRequest, ServletResponse ignoredResponse) ->
                continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getHeader("X-Rate-Limit-Limit")).isEqualTo("6");
        assertThat(response.getHeader("X-Rate-Limit-Remaining")).isEqualTo("5");
    }

    @Test
    void rejectsRequestWhenUserIsOverLimit() throws Exception {
        RateLimitingFilter filter = filter(new RateLimitDecision(false, 0, Duration.ofSeconds(12)), Optional.of("user:alice"));
        MockHttpServletRequest request = chatRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (ServletRequest ignoredRequest, ServletResponse ignoredResponse) ->
                continued.set(true));

        assertThat(continued).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("12");
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void skipsUnauthenticatedRequest() throws Exception {
        RateLimitingFilter filter = filter(new RateLimitDecision(false, 0, Duration.ofSeconds(12)), Optional.empty());
        MockHttpServletRequest request = chatRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (ServletRequest ignoredRequest, ServletResponse ignoredResponse) ->
                continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    void skipsUnprotectedRequest() throws Exception {
        RateLimitingFilter filter = filter(new RateLimitDecision(false, 0, Duration.ofSeconds(12)), Optional.of("user:alice"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/context");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (ServletRequest ignoredRequest, ServletResponse ignoredResponse) ->
                continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    private RateLimitingFilter filter(RateLimitDecision decision, Optional<String> key) {
        return new RateLimitingFilter(
                properties,
                ignoredRequest -> key,
                new RateLimitService() {
                    @Override
                    public RateLimitDecision consume(String ignoredKey) {
                        return decision;
                    }

                    @Override
                    public RateLimitStatus status(String ignoredKey) {
                        return new RateLimitStatus(decision.remainingTokens(), properties.getRequestsPerMinute());
                    }
                }
        );
    }

    private MockHttpServletRequest chatRequest() {
        return new MockHttpServletRequest("POST", "/api/chat");
    }
}
