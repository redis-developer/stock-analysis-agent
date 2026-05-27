package com.redis.stockanalysisagent.ratelimiting;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "app.rate-limiting", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitingProperties properties;
    private final RateLimitKeyResolver keyResolver;
    private final RateLimitService rateLimitService;

    public RateLimitingFilter(
            RateLimitingProperties properties,
            RateLimitKeyResolver keyResolver,
            RateLimitService rateLimitService
    ) {
        this.properties = properties;
        this.keyResolver = keyResolver;
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !isProtectedRequest(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Optional<String> key = keyResolver.resolve(request);
        if (key.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = rateLimitService.consume(key.get());
        response.setHeader("X-Rate-Limit-Limit", String.valueOf(properties.requireRequestsPerMinute()));
        response.setHeader("X-Rate-Limit-Remaining", String.valueOf(decision.remainingTokens()));

        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = decision.retryAfterSeconds();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"Rate limit exceeded. Try again later.\"}");
    }

    private boolean isProtectedRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }

        return properties.getProtectedPaths().contains(pathWithinApplication(request));
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }

        return requestUri;
    }
}
