package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.session.ChatSessionAccess;
import com.redis.stockanalysisagent.session.controller.ChatSessionController;
import com.redis.stockanalysisagent.session.ChatSessionService;
import com.redis.stockanalysisagent.session.controller.vo.ChatSessionsResponse;
import com.redis.stockanalysisagent.session.controller.vo.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTests {

    private final ChatService chatService = mock(ChatService.class);
    private final ChatProgressPublisher progressPublisher = mock(ChatProgressPublisher.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final ChatSessionAccess sessionAccess = new ChatSessionAccess(true);
    private final ChatController controller = new ChatController(chatService, sessionAccess, progressPublisher);
    private final ChatSessionController sessionController = new ChatSessionController(chatSessionService, sessionAccess);

    @Test
    void chatUsesSessionUserAndSessionRetrievedMemoriesLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 6, null, null, null), request);
        when(chatService.chat(eq("alice"), eq("session-1"), eq("hello"), eq(6), eq(true), eq(true)))
                .thenReturn(new ChatService.ChatTurn(
                        "alice:session-1",
                        "response",
                        List.of(),
                        false,
                        false,
                        null,
                        List.of()
                ));

        ChatResponse response = controller.chat(new ChatRequest("session-1", " hello ", null, null, null, null), request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo("alice");
        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.retrievedMemoriesLimit()).isEqualTo(6);
        assertThat(response.apiCachingEnabled()).isTrue();
        assertThat(response.semanticCachingEnabled()).isTrue();
        assertThat(response.rateLimitingEnabled()).isTrue();
        verify(chatService).chat("alice", "session-1", "hello", 6, true, true);
    }

    @Test
    void chatWithoutLoginIsUnauthorized() {
        assertThatThrownBy(() -> controller.chat(new ChatRequest("session-1", "hello", null, null, null, null), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void chatUsesLoggedInUserWhenSessionManagementIsDisabled() {
        ChatSessionAccess disabledAccess = new ChatSessionAccess(false);
        ChatController disabledController = new ChatController(
                chatService,
                disabledAccess,
                progressPublisher
        );
        ChatSessionController disabledSessionController = new ChatSessionController(chatSessionService, disabledAccess);
        MockHttpServletRequest request = new MockHttpServletRequest();
        disabledSessionController.login(new LoginRequest("alice", 7, null, null, null), request);
        when(chatService.chat(eq("alice"), eq("session-1"), eq("hello"), eq(4), eq(false), eq(false)))
                .thenReturn(new ChatService.ChatTurn(
                        "alice:session-1",
                        "response",
                        List.of(),
                        false,
                        false,
                        null,
                        List.of()
                ));

        ChatResponse response = disabledController.chat(new ChatRequest("session-1", "hello", 4, false, false, false), request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo("alice");
        assertThat(response.retrievedMemoriesLimit()).isEqualTo(4);
        assertThat(response.apiCachingEnabled()).isFalse();
        assertThat(response.semanticCachingEnabled()).isFalse();
        assertThat(response.rateLimitingEnabled()).isFalse();
        verify(chatService).chat("alice", "session-1", "hello", 4, false, false);
    }

    @Test
    void chatAddsSessionToCachedList() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null), request);
        when(chatSessionService.listSessions("alice")).thenReturn(List.of("session-1"));
        when(chatService.chat(eq("alice"), eq("session-2"), eq("hello"), eq(7), eq(true), eq(true)))
                .thenReturn(new ChatService.ChatTurn(
                        "alice:session-2",
                        "response",
                        List.of(),
                        false,
                        false,
                        null,
                        List.of()
                ));

        sessionController.sessions(request, false);
        controller.chat(new ChatRequest("session-2", "hello", null, null, null, null), request);
        ChatSessionsResponse response = sessionController.sessions(request, false).getBody();

        assertThat(response).isNotNull();
        assertThat(response.sessions()).containsExactly("session-2", "session-1");
        verify(chatSessionService, times(1)).listSessions("alice");
    }

    @Test
    void chatStreamWritesProgressAndFinalResponse() throws Exception {
        ChatProgressPublisher realProgressPublisher = new ChatProgressPublisher();
        ChatController streamingController = new ChatController(chatService, sessionAccess, realProgressPublisher);
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null), request);
        doAnswer(invocation -> {
            realProgressPublisher.running(
                    "TEST_STEP",
                    "Test step",
                    ChatProgressPublisher.KIND_SYSTEM,
                    "Testing progress."
            );
            realProgressPublisher.completed(
                    "TEST_STEP",
                    "Test step",
                    ChatProgressPublisher.KIND_SYSTEM,
                    25,
                    "Tested progress.",
                    new TokenUsageSummary(10, 5, 15)
            );
            return new ChatService.ChatTurn(
                    "alice:session-1",
                    "response",
                    List.of(),
                    false,
                    false,
                    null,
                    List.of()
            );
        }).when(chatService).chat("alice", "session-1", "hello", 7, true, true);

        StreamingResponseBody body = streamingController.chatStream(
                new ChatRequest("session-1", "hello", null, null, null, null),
                request,
                new MockHttpServletResponse()
        ).getBody();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        assertThat(body).isNotNull();
        body.writeTo(outputStream);

        String stream = outputStream.toString();
        assertThat(stream).contains("\"type\":\"progress\"");
        assertThat(stream).contains("\"id\":\"TEST_STEP\"");
        assertThat(stream).contains("\"totalTokens\":15");
        assertThat(stream).contains("\"type\":\"final\"");
        assertThat(stream).contains("\"response\":\"response\"");
    }
}
