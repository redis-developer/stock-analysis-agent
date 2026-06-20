package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.cache.ExternalApiUsageService;
import com.redis.stockanalysisagent.cache.ExternalApiUsageSnapshot;
import com.redis.stockanalysisagent.session.ChatSessionAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final ChatService chatService;
    private final ChatSessionAccess sessionAccess;
    private final ChatProgressPublisher progressPublisher;
    private final ExternalApiUsageService apiUsageService;

    public ChatController(
            ChatService chatService,
            ChatSessionAccess sessionAccess,
            ChatProgressPublisher progressPublisher
    ) {
        this(chatService, sessionAccess, progressPublisher, (ExternalApiUsageService) null);
    }

    @Autowired
    public ChatController(
            ChatService chatService,
            ChatSessionAccess sessionAccess,
            ChatProgressPublisher progressPublisher,
            ObjectProvider<ExternalApiUsageService> apiUsageService
    ) {
        this(chatService, sessionAccess, progressPublisher, apiUsageService.getIfAvailable());
    }

    private ChatController(
            ChatService chatService,
            ChatSessionAccess sessionAccess,
            ChatProgressPublisher progressPublisher,
            ExternalApiUsageService apiUsageService
    ) {
        this.chatService = chatService;
        this.sessionAccess = sessionAccess;
        this.progressPublisher = progressPublisher;
        this.apiUsageService = apiUsageService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(executeChat(prepareChat(request, httpRequest)));
    }

    @PostMapping(value = "/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        PreparedChatRequest prepared = prepareChat(request, httpRequest);
        prepareStreamingResponse(httpResponse);
        StreamingResponseBody body = outputStream -> {
            try {
                ChatResponse response = progressPublisher.capture(
                        event -> writeJsonLine(outputStream, httpResponse, event),
                        () -> executeChat(prepared)
                );
                writeJsonLine(outputStream, httpResponse, ChatProgressEvent.finalResponse(response));
            } catch (UncheckedIOException ignored) {
            } catch (RuntimeException ex) {
                writeJsonLine(outputStream, httpResponse, ChatProgressEvent.error(errorMessage(ex)));
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(body);
    }

    private void prepareStreamingResponse(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setBufferSize(1);
    }

    private PreparedChatRequest prepareChat(ChatRequest request, HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        String userId = sessionAccess.activeUserId(session);
        String sessionId = sessionAccess.normalizeSessionId(request.sessionId());
        int retrievedMemoriesLimit = request.retrievedMemoriesLimit() != null
                ? sessionAccess.normalizeRetrievedMemoriesLimit(request.retrievedMemoriesLimit())
                : sessionAccess.activeRetrievedMemoriesLimit(session);
        boolean apiCachingEnabled = request.apiCachingEnabled() != null
                ? sessionAccess.normalizeCachingEnabled(request.apiCachingEnabled())
                : sessionAccess.activeApiCachingEnabled(session);
        boolean semanticCachingEnabled = request.semanticCachingEnabled() != null
                ? sessionAccess.normalizeCachingEnabled(request.semanticCachingEnabled())
                : sessionAccess.activeSemanticCachingEnabled(session);
        boolean rateLimitingEnabled = request.rateLimitingEnabled() != null
                ? sessionAccess.normalizeRateLimitingEnabled(request.rateLimitingEnabled())
                : sessionAccess.activeRateLimitingEnabled(session);
        if (sessionAccess.sessionManagementEnabled() && session != null) {
            sessionAccess.storeRetrievedMemoriesLimit(session, retrievedMemoriesLimit);
            sessionAccess.storeApiCachingEnabled(session, apiCachingEnabled);
            sessionAccess.storeSemanticCachingEnabled(session, semanticCachingEnabled);
            sessionAccess.storeRateLimitingEnabled(session, rateLimitingEnabled);
        }

        return new PreparedChatRequest(
                session,
                userId,
                sessionId,
                request.message().trim(),
                normalizeClientRequestId(request.clientRequestId()),
                retrievedMemoriesLimit,
                apiCachingEnabled,
                semanticCachingEnabled,
                rateLimitingEnabled
        );
    }

    private ChatResponse executeChat(PreparedChatRequest prepared) {
        long startedAt = System.nanoTime();
        ChatService.ChatTurn turn = chatService.chat(
                prepared.userId(),
                prepared.sessionId(),
                prepared.message(),
                prepared.clientRequestId(),
                prepared.retrievedMemoriesLimit(),
                prepared.apiCachingEnabled(),
                prepared.semanticCachingEnabled()
        );
        long responseTimeMs = (System.nanoTime() - startedAt) / 1_000_000;
        sessionAccess.cacheChatSession(prepared.session(), prepared.sessionId());

        return new ChatResponse(
                prepared.userId(),
                prepared.sessionId(),
                turn.conversationId(),
                turn.response(),
                turn.retrievedMemories(),
                turn.fromSemanticCache(),
                turn.fromSemanticGuardrail(),
                turn.tokenUsage(),
                turn.executionSteps(),
                prepared.retrievedMemoriesLimit(),
                prepared.apiCachingEnabled(),
                prepared.semanticCachingEnabled(),
                prepared.rateLimitingEnabled(),
                providerUsageSnapshot(),
                responseTimeMs,
                turn.tickers(),
                turn.triggeredAgents(),
                turn.workflowId(),
                turn.workflowStatus()
        );
    }

    private ExternalApiUsageSnapshot providerUsageSnapshot() {
        if (apiUsageService == null) {
            return ExternalApiUsageSnapshot.empty();
        }

        return apiUsageService.snapshot();
    }

    private String normalizeClientRequestId(String clientRequestId) {
        return clientRequestId == null || clientRequestId.isBlank() ? null : clientRequestId.trim();
    }

    private void writeJsonLine(OutputStream outputStream, HttpServletResponse response, ChatProgressEvent event) {
        try {
            OBJECT_MAPPER.writeValue(outputStream, event);
            outputStream.write('\n');
            outputStream.flush();
            response.flushBuffer();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record PreparedChatRequest(
            HttpSession session,
            String userId,
            String sessionId,
            String message,
            String clientRequestId,
            int retrievedMemoriesLimit,
            boolean apiCachingEnabled,
            boolean semanticCachingEnabled,
            boolean rateLimitingEnabled
    ) {
    }
}
