package com.redis.stockanalysisagent.cache.controller;

import com.redis.stockanalysisagent.cache.CacheInspectionService;
import com.redis.stockanalysisagent.session.ChatSessionAccess;
import com.redis.stockanalysisagent.session.controller.ChatSessionController;
import com.redis.stockanalysisagent.session.ChatSessionService;
import com.redis.stockanalysisagent.session.controller.vo.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCacheControllerTests {

    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final CacheInspectionService cacheInspectionService = mock(CacheInspectionService.class);
    private final ChatSessionAccess sessionAccess = new ChatSessionAccess(true);
    private final ChatCacheController controller = new ChatCacheController(cacheInspectionService, sessionAccess);
    private final ChatSessionController sessionController = new ChatSessionController(chatSessionService, sessionAccess);

    @Test
    void cacheEndpointRequiresLoginWhenSessionManagementIsEnabled() {
        assertThatThrownBy(() -> controller.cache(new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void cacheEndpointReturnsInspectionForLoggedInUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null), request);
        CacheInspectionService.CacheContents contents = new CacheInspectionService.CacheContents(List.of(
                new CacheInspectionService.CacheGroup("market-data-quotes", 1, false, List.of(
                        new CacheInspectionService.CacheEntry("AAPL", 30L, "MarketSnapshot", 2, false, "{}")
                ))
        ));
        when(cacheInspectionService.inspect()).thenReturn(contents);

        CacheInspectionService.CacheContents response = controller.cache(request).getBody();

        assertThat(response).isSameAs(contents);
        verify(cacheInspectionService).inspect();
    }

    @Test
    void cacheEndpointAllowsAnonymousAccessWhenSessionManagementIsDisabled() {
        ChatCacheController disabledController = new ChatCacheController(
                cacheInspectionService,
                new ChatSessionAccess(false)
        );
        CacheInspectionService.CacheContents contents = new CacheInspectionService.CacheContents(List.of());
        when(cacheInspectionService.inspect()).thenReturn(contents);

        CacheInspectionService.CacheContents response = disabledController.cache(new MockHttpServletRequest()).getBody();

        assertThat(response).isSameAs(contents);
        verify(cacheInspectionService).inspect();
    }

    @Test
    void deleteCacheEntryRequiresLoginWhenSessionManagementIsEnabled() {
        assertThatThrownBy(() -> controller.deleteCacheEntry("market-data-quotes", "AAPL", new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void deleteCacheEntryUsesCacheNameAndKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null), request);
        when(cacheInspectionService.deleteEntry("market-data-quotes", "AAPL")).thenReturn(true);

        controller.deleteCacheEntry("market-data-quotes", "AAPL", request);

        verify(cacheInspectionService).deleteEntry("market-data-quotes", "AAPL");
    }

    @Test
    void deleteCacheEntryReturnsNotFoundWhenEntryDoesNotExist() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null), request);
        when(cacheInspectionService.deleteEntry("market-data-quotes", "AAPL")).thenReturn(false);

        assertThatThrownBy(() -> controller.deleteCacheEntry("market-data-quotes", "AAPL", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
