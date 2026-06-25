package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.session.ChatSessionAccess;
import com.redis.stockanalysisagent.session.controller.ChatSessionController;
import com.redis.stockanalysisagent.session.ChatSessionService;
import com.redis.stockanalysisagent.session.controller.vo.LoginRequest;
import com.redis.stockanalysisagent.workflow.events.WorkflowEventService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTests {

    private final ChatService chatService = mock(ChatService.class);
    private final WorkflowProgress workflowProgress = mock(WorkflowProgress.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final ChatSessionAccess sessionAccess = new ChatSessionAccess(true);
    private final ChatController controller = new ChatController(chatService, sessionAccess, workflowProgress);
    private final ChatSessionController sessionController = new ChatSessionController(chatSessionService, sessionAccess);

    @Test
    void chatUsesSessionUserAndSessionRetrievedMemoriesLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 6, null, null, null, null, null), request);
        ChatService.ChatRunRequest chatRequest = chatRunRequest("alice", "session-1", "hello", null, 6, true, true);
        when(chatService.run(eq(chatRequest))).thenReturn(chatTurn("alice:session-1"));

        ChatResponse response = controller.chat(new ChatRequest("session-1", " hello ", null, null, null, null, null, null, null), request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo("alice");
        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.retrievedMemoriesLimit()).isEqualTo(6);
        assertThat(response.apiCachingEnabled()).isTrue();
        assertThat(response.semanticCachingEnabled()).isTrue();
        assertThat(response.rateLimitingEnabled()).isTrue();
        assertThat(response.requireApprovalEnabled()).isFalse();
        assertThat(response.approvalRequiredTools()).isEmpty();
        verify(chatService).run(chatRequest);
    }

    @Test
    void chatPassesClientRequestIdAndReturnsWorkflowFields() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 6, null, null, null, null, null), request);
        ChatService.ChatRunRequest chatRequest = chatRunRequest("alice", "session-1", "hello", "client-1", 6, true, true);
        when(chatService.run(eq(chatRequest))).thenReturn(chatTurn("alice:session-1", "workflow-1", WorkflowStatus.COMPLETED));

        ChatResponse response = controller.chat(
                new ChatRequest("session-1", "hello", null, null, null, null, null, null, " client-1 "),
                request
        ).getBody();

        assertThat(response).isNotNull();
        assertThat(response.workflowId()).isEqualTo("workflow-1");
        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        verify(chatService).run(chatRequest);
    }

    @Test
    void chatWithoutLoginIsUnauthorized() {
        assertThatThrownBy(() -> controller.chat(new ChatRequest("session-1", "hello", null, null, null, null, null, null, null), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void chatUsesLoggedInUserWhenSessionManagementIsDisabled() {
        ChatSessionAccess disabledAccess = new ChatSessionAccess(false);
        ChatController disabledController = new ChatController(
                chatService,
                disabledAccess,
                workflowProgress
        );
        ChatSessionController disabledSessionController = new ChatSessionController(chatSessionService, disabledAccess);
        MockHttpServletRequest request = new MockHttpServletRequest();
        disabledSessionController.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        ChatService.ChatRunRequest chatRequest = chatRunRequest("alice", "session-1", "hello", null, 4, false, false);
        when(chatService.run(eq(chatRequest))).thenReturn(chatTurn("alice:session-1"));

        ChatResponse response = disabledController.chat(new ChatRequest("session-1", "hello", 4, false, false, false, false, null, null), request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo("alice");
        assertThat(response.retrievedMemoriesLimit()).isEqualTo(4);
        assertThat(response.apiCachingEnabled()).isFalse();
        assertThat(response.semanticCachingEnabled()).isFalse();
        assertThat(response.rateLimitingEnabled()).isFalse();
        assertThat(response.requireApprovalEnabled()).isFalse();
        assertThat(response.approvalRequiredTools()).isEmpty();
        verify(chatService).run(chatRequest);
    }

    @Test
    void chatStreamWritesProgressAndFinalResponse() throws Exception {
        WorkflowProgress realWorkflowProgress = new WorkflowProgress(mock(WorkflowEventService.class));
        ChatController streamingController = new ChatController(chatService, sessionAccess, realWorkflowProgress);
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        doAnswer(invocation -> {
            realWorkflowProgress.running(
                    "TEST_STEP",
                    "Test step",
                    WorkflowProgress.KIND_SYSTEM,
                    "Testing progress."
            );
            realWorkflowProgress.completed(
                    "TEST_STEP",
                    "Test step",
                    WorkflowProgress.KIND_SYSTEM,
                    25,
                    "Tested progress.",
                    new TokenUsageSummary(10, 5, 15)
            );
            return chatTurn("alice:session-1");
        }).when(chatService).run(chatRunRequest("alice", "session-1", "hello", null, 7, true, true));

        StreamingResponseBody body = streamingController.chatStream(
                new ChatRequest("session-1", "hello", null, null, null, null, null, null, null),
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

    private ChatService.ChatTurn chatTurn(String conversationId) {
        return chatTurn(conversationId, null, null);
    }

    private ChatService.ChatRunRequest chatRunRequest(
            String userId,
            String sessionId,
            String message,
            String clientRequestId,
            int retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled
    ) {
        return new ChatService.ChatRunRequest(
                userId,
                sessionId,
                message,
                clientRequestId,
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                List.of(),
                "chat",
                null,
                null,
                true,
                null
        );
    }

    private ChatService.ChatTurn chatTurn(String conversationId, String workflowId, WorkflowStatus workflowStatus) {
        return new ChatService.ChatTurn(
                conversationId,
                "response",
                List.of(),
                false,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                workflowId,
                workflowStatus,
                null
        );
    }
}
