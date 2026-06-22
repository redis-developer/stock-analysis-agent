package com.redis.stockanalysisagent.session;
import com.redis.stockanalysisagent.session.controller.vo.ChatContextResponse;
import com.redis.stockanalysisagent.session.controller.ChatSessionController;
import com.redis.stockanalysisagent.session.controller.vo.ChatSessionResponse;
import com.redis.stockanalysisagent.session.controller.vo.ChatSessionsResponse;
import com.redis.stockanalysisagent.session.controller.vo.ChatSettingsRequest;
import com.redis.stockanalysisagent.session.controller.vo.LoginRequest;
import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;
import com.redis.stockanalysisagent.session.dto.ChatSessionSummary;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionControllerTests {

    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final ChatSessionController controller = new ChatSessionController(
            chatSessionService,
            new ChatSessionAccess(true)
    );

    @Test
    void contextWithoutLoginDoesNotSetActiveUser() {
        ChatContextResponse response = controller.context(new MockHttpServletRequest()).getBody();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isNull();
        assertThat(response.retrievedMemoriesLimit()).isEqualTo(ChatSessionAccess.DEFAULT_RETRIEVED_MEMORIES_LIMIT);
        assertThat(response.apiCachingEnabled()).isTrue();
        assertThat(response.semanticCachingEnabled()).isTrue();
        assertThat(response.rateLimitingEnabled()).isTrue();
        assertThat(response.rateLimitLimit()).isEqualTo(6);
        assertThat(response.rateLimitRemaining()).isEqualTo(6);
        assertThat(response.sessionManagementEnabled()).isTrue();
    }

    @Test
    void loginStoresUserAndRetrievedMemoriesLimitInSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ChatContextResponse response = controller.login(new LoginRequest("alice", 7, false, false, false, false, null), request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo("alice");
        assertThat(response.retrievedMemoriesLimit()).isEqualTo(7);
        assertThat(response.apiCachingEnabled()).isFalse();
        assertThat(response.semanticCachingEnabled()).isFalse();
        assertThat(response.rateLimitingEnabled()).isFalse();
        assertThat(response.rateLimitLimit()).isEqualTo(6);
        assertThat(response.rateLimitRemaining()).isEqualTo(6);
        assertThat(controller.context(request).getBody().userId()).isEqualTo("alice");
        assertThat(controller.context(request).getBody().retrievedMemoriesLimit()).isEqualTo(7);
        assertThat(controller.context(request).getBody().apiCachingEnabled()).isFalse();
        assertThat(controller.context(request).getBody().semanticCachingEnabled()).isFalse();
        assertThat(controller.context(request).getBody().rateLimitingEnabled()).isFalse();
    }

    @Test
    void loginRecordsNormalizedUserInRedisTracking() {
        LoginUserTrackingService loginUserTrackingService = mock(LoginUserTrackingService.class);
        ChatSessionController trackingController = new ChatSessionController(
                chatSessionService,
                new ChatSessionAccess(true),
                loginUserTrackingService
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, "session-1"));
        request.addHeader("X-Forwarded-For", "203.0.113.42, 10.0.0.1");
        request.addHeader("User-Agent", "Mozilla/5.0");
        request.addHeader("Accept-Language", "en-US,en;q=0.9");

        trackingController.login(new LoginRequest(" alice ", 7, null, null, null, null, null), request);

        verify(loginUserTrackingService).recordLogin(
                "alice",
                "203.0.113.42",
                "Mozilla/5.0",
                "en-US,en;q=0.9",
                "session-1"
        );
    }

    @Test
    void settingsStoresCachePreferencesInSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(new LoginRequest("alice", 7, null, null, null, null, null), request);

        ChatContextResponse response = controller.settings(new ChatSettingsRequest(5, false, false, false, false, null), request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.retrievedMemoriesLimit()).isEqualTo(5);
        assertThat(response.apiCachingEnabled()).isFalse();
        assertThat(response.semanticCachingEnabled()).isFalse();
        assertThat(response.rateLimitingEnabled()).isFalse();
        assertThat(controller.context(request).getBody().apiCachingEnabled()).isFalse();
        assertThat(controller.context(request).getBody().semanticCachingEnabled()).isFalse();
        assertThat(controller.context(request).getBody().rateLimitingEnabled()).isFalse();
    }

    @Test
    void contextReturnsDisabledSessionManagement() {
        ChatSessionController disabledController = new ChatSessionController(
                chatSessionService,
                new ChatSessionAccess(false)
        );
        ChatContextResponse response = disabledController.context(new MockHttpServletRequest()).getBody();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isNull();
        assertThat(response.retrievedMemoriesLimit()).isEqualTo(ChatSessionAccess.DEFAULT_RETRIEVED_MEMORIES_LIMIT);
        assertThat(response.apiCachingEnabled()).isTrue();
        assertThat(response.semanticCachingEnabled()).isTrue();
        assertThat(response.rateLimitingEnabled()).isTrue();
        assertThat(response.rateLimitLimit()).isEqualTo(6);
        assertThat(response.rateLimitRemaining()).isEqualTo(6);
        assertThat(response.sessionManagementEnabled()).isFalse();
    }

    @Test
    void sessionsEndpointReadsSessionIndex() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        when(chatSessionService.listSessions("alice")).thenReturn(List.of("session-2", "session-1"));
        when(chatSessionService.summarizeSessions("alice", List.of("session-2", "session-1")))
                .thenReturn(List.of(
                        new ChatSessionSummary("session-2", "2026-06-22T18:11:27Z"),
                        new ChatSessionSummary("session-1", "2026-06-22T18:09:50Z")
                ));

        ChatSessionsResponse firstResponse = controller.sessions(request, false).getBody();
        ChatSessionsResponse secondResponse = controller.sessions(request, false).getBody();

        assertThat(firstResponse).isNotNull();
        assertThat(firstResponse.sessions()).containsExactly("session-2", "session-1");
        assertThat(firstResponse.sessionDetails())
                .extracting(ChatSessionSummary::sessionId)
                .containsExactly("session-2", "session-1");
        assertThat(secondResponse).isNotNull();
        assertThat(secondResponse.sessions()).containsExactly("session-2", "session-1");
        verify(chatSessionService, times(2)).listSessions("alice");
        verify(chatSessionService, times(2)).summarizeSessions("alice", List.of("session-2", "session-1"));
    }

    @Test
    void forceRefreshReloadsCachedSessions() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        when(chatSessionService.listSessions("alice"))
                .thenReturn(List.of("session-1"))
                .thenReturn(List.of("session-2", "session-1"));

        controller.sessions(request, false);
        ChatSessionsResponse refreshedResponse = controller.sessions(request, true).getBody();

        assertThat(refreshedResponse).isNotNull();
        assertThat(refreshedResponse.sessions()).containsExactly("session-2", "session-1");
        verify(chatSessionService, times(2)).listSessions("alice");
    }

    @Test
    void sessionEndpointReturnsSessionMetadata() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        when(chatSessionService.getSession("alice", "session-1"))
                .thenReturn(new ChatSessionService.ChatSessionView(
                        List.of(),
                        new ChatSessionMetadata(List.of("AAPL"), List.of("MARKET_DATA"))
                ));

        ChatSessionResponse response = controller.session("session-1", request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.metadata().tickers()).containsExactly("AAPL");
        assertThat(response.metadata().triggeredAgents()).containsExactly("MARKET_DATA");
        verify(chatSessionService).getSession("alice", "session-1");
    }

    @Test
    void clearSessionRemovesSessionFromIndex() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        controller.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        when(chatSessionService.listSessions("alice"))
                .thenReturn(List.of("session-2", "session-1"))
                .thenReturn(List.of("session-1"));

        controller.sessions(request, false);
        controller.clearSession("session-2", request);
        ChatSessionsResponse response = controller.sessions(request, false).getBody();

        assertThat(response).isNotNull();
        assertThat(response.sessions()).containsExactly("session-1");
        verify(chatSessionService).clearSession("alice", "session-2");
        verify(chatSessionService, times(2)).listSessions("alice");
    }

    @Test
    void sessionsEndpointIsForbiddenWhenSessionManagementIsDisabled() {
        ChatSessionController disabledController = new ChatSessionController(
                chatSessionService,
                new ChatSessionAccess(false)
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        disabledController.login(new LoginRequest("alice", 7, null, null, null, null, null), request);

        assertThatThrownBy(() -> disabledController.sessions(request, false))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
