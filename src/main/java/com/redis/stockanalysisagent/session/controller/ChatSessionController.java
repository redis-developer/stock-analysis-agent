package com.redis.stockanalysisagent.session.controller;

import com.redis.stockanalysisagent.cache.ExternalApiUsageService;
import com.redis.stockanalysisagent.cache.ExternalApiUsageSnapshot;
import com.redis.stockanalysisagent.ratelimiting.RateLimitStatus;
import com.redis.stockanalysisagent.ratelimiting.RateLimitStatusProvider;
import com.redis.stockanalysisagent.session.*;
import com.redis.stockanalysisagent.session.controller.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final ChatSessionAccess sessionAccess;
    private final RateLimitStatusProvider rateLimitStatusProvider;
    private final ExternalApiUsageService apiUsageService;

    public ChatSessionController(
            ChatSessionService chatSessionService,
            ChatSessionAccess sessionAccess
    ) {
        this(chatSessionService, sessionAccess, (RateLimitStatusProvider) null, (ExternalApiUsageService) null);
    }

    @Autowired
    public ChatSessionController(
            ChatSessionService chatSessionService,
            ChatSessionAccess sessionAccess,
            ObjectProvider<RateLimitStatusProvider> rateLimitStatusProvider,
            ObjectProvider<ExternalApiUsageService> apiUsageService
    ) {
        this(
                chatSessionService,
                sessionAccess,
                rateLimitStatusProvider.getIfAvailable(),
                apiUsageService.getIfAvailable()
        );
    }

    private ChatSessionController(
            ChatSessionService chatSessionService,
            ChatSessionAccess sessionAccess,
            RateLimitStatusProvider rateLimitStatusProvider,
            ExternalApiUsageService apiUsageService
    ) {
        this.chatSessionService = chatSessionService;
        this.sessionAccess = sessionAccess;
        this.rateLimitStatusProvider = rateLimitStatusProvider;
        this.apiUsageService = apiUsageService;
    }

    @PostMapping("/login")
    public ResponseEntity<ChatContextResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        HttpSession session = httpRequest.getSession(true);
        String userId = sessionAccess.requireUserId(request.userId());
        sessionAccess.storeUserId(session, userId);
        sessionAccess.storeRetrievedMemoriesLimit(
                session,
                sessionAccess.normalizeRetrievedMemoriesLimit(request.retrievedMemoriesLimit())
        );
        sessionAccess.storeApiCachingEnabled(
                session,
                sessionAccess.normalizeCachingEnabled(request.apiCachingEnabled())
        );
        sessionAccess.storeSemanticCachingEnabled(
                session,
                sessionAccess.normalizeCachingEnabled(request.semanticCachingEnabled())
        );
        sessionAccess.storeRateLimitingEnabled(
                session,
                sessionAccess.normalizeRateLimitingEnabled(request.rateLimitingEnabled())
        );
        sessionAccess.clearCachedChatSessions(session);
        return ResponseEntity.ok(contextResponse(session));
    }

    @PostMapping("/settings")
    public ResponseEntity<ChatContextResponse> settings(
            @Valid @RequestBody ChatSettingsRequest request,
            HttpServletRequest httpRequest
    ) {
        HttpSession session = httpRequest.getSession(false);
        sessionAccess.requireSessionUserId(session);
        sessionAccess.storeRetrievedMemoriesLimit(
                session,
                sessionAccess.normalizeRetrievedMemoriesLimit(request.retrievedMemoriesLimit())
        );
        sessionAccess.storeApiCachingEnabled(
                session,
                sessionAccess.normalizeCachingEnabled(request.apiCachingEnabled())
        );
        sessionAccess.storeSemanticCachingEnabled(
                session,
                sessionAccess.normalizeCachingEnabled(request.semanticCachingEnabled())
        );
        sessionAccess.storeRateLimitingEnabled(
                session,
                sessionAccess.normalizeRateLimitingEnabled(request.rateLimitingEnabled())
        );
        return ResponseEntity.ok(contextResponse(session));
    }

    @GetMapping("/context")
    public ResponseEntity<ChatContextResponse> context(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        return ResponseEntity.ok(contextResponse(session));
    }

    @PostMapping("/provider-usage/{provider}/reset")
    public ResponseEntity<ExternalApiUsageSnapshot> resetProviderUsage(
            @PathVariable String provider,
            HttpServletRequest httpRequest
    ) {
        HttpSession session = httpRequest.getSession(false);
        sessionAccess.requireSessionUserId(session);
        try {
            return ResponseEntity.ok(resetProviderUsage(provider));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<ChatSessionsResponse> sessions(
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "false") boolean forceRefresh
    ) {
        sessionAccess.requireSessionManagementEnabled();
        HttpSession session = httpRequest.getSession(false);
        String userId = sessionAccess.requireSessionUserId(session);
        List<String> sessions = sessionAccess.cachedChatSessions(session);
        if (forceRefresh || sessions == null) {
            sessions = sessionAccess.normalizeSessionIds(chatSessionService.listSessions(userId));
            sessionAccess.storeCachedChatSessions(session, sessions);
        }
        return ResponseEntity.ok(new ChatSessionsResponse(sessions));
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<ChatSessionResponse> session(
            @PathVariable String sessionId,
            HttpServletRequest httpRequest
    ) {
        sessionAccess.requireSessionManagementEnabled();
        HttpSession session = httpRequest.getSession(false);
        String normalizedUserId = sessionAccess.requireSessionUserId(session);
        String normalizedSessionId = sessionAccess.normalizeSessionId(sessionId);
        return ResponseEntity.ok(new ChatSessionResponse(
                normalizedUserId,
                normalizedSessionId,
                chatSessionService.getSessionMessages(normalizedUserId, normalizedSessionId)
        ));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(
            @PathVariable String sessionId,
            HttpServletRequest httpRequest
    ) {
        sessionAccess.requireSessionManagementEnabled();
        HttpSession session = httpRequest.getSession(false);
        String userId = sessionAccess.requireSessionUserId(session);
        String normalizedSessionId = sessionAccess.requireExistingSessionId(sessionId);
        chatSessionService.clearSession(userId, normalizedSessionId);
        sessionAccess.removeCachedChatSession(session, normalizedSessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    private ChatContextResponse contextResponse(HttpSession session) {
        RateLimitStatus rateLimitStatus = rateLimitStatus(session);
        return new ChatContextResponse(
                sessionAccess.defaultRetrievedMemoriesLimit(),
                sessionAccess.sessionUserId(session),
                sessionAccess.sessionRetrievedMemoriesLimit(session),
                sessionAccess.sessionApiCachingEnabled(session),
                sessionAccess.sessionSemanticCachingEnabled(session),
                sessionAccess.sessionRateLimitingEnabled(session),
                rateLimitStatus.limit(),
                rateLimitStatus.remainingTokens(),
                providerUsageSnapshot(),
                sessionAccess.sessionManagementEnabled()
        );
    }

    private ExternalApiUsageSnapshot resetProviderUsage(String provider) {
        if (apiUsageService == null) {
            return ExternalApiUsageSnapshot.empty();
        }

        return apiUsageService.reset(provider);
    }

    private ExternalApiUsageSnapshot providerUsageSnapshot() {
        if (apiUsageService == null) {
            return ExternalApiUsageSnapshot.empty();
        }

        return apiUsageService.snapshot();
    }

    private RateLimitStatus rateLimitStatus(HttpSession session) {
        if (rateLimitStatusProvider != null) {
            return rateLimitStatusProvider.status(session);
        }

        return new RateLimitStatus(6, 6);
    }
}
