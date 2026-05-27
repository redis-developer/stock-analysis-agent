package com.redis.stockanalysisagent.memory.controller;

import com.redis.agentmemory.models.longtermemory.MemoryRecordResult;
import com.redis.stockanalysisagent.memory.controller.vo.*;
import com.redis.stockanalysisagent.memory.service.AgentMemoryService;
import com.redis.stockanalysisagent.session.ChatSessionAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatMemoryController {

    private final AgentMemoryService agentMemoryService;
    private final ChatSessionAccess sessionAccess;

    public ChatMemoryController(
            AgentMemoryService agentMemoryService,
            ChatSessionAccess sessionAccess
    ) {
        this.agentMemoryService = agentMemoryService;
        this.sessionAccess = sessionAccess;
    }

    @GetMapping("/memories")
    public ResponseEntity<LongTermMemoriesResponse> memories(
            HttpServletRequest httpRequest
    ) {
        sessionAccess.requireSessionManagementEnabled();
        HttpSession session = httpRequest.getSession(false);
        String userId = sessionAccess.requireSessionUserId(session);
        List<LongTermMemoryResponse> memories = agentMemoryService.listLongTermMemories(userId).stream()
                .map(this::toLongTermMemoryResponse)
                .toList();
        return ResponseEntity.ok(new LongTermMemoriesResponse(memories));
    }

    @PostMapping("/memories")
    public ResponseEntity<LongTermMemoryCreateResponse> createMemory(
            @Valid @RequestBody LongTermMemoryCreateRequest request,
            HttpServletRequest httpRequest
    ) {
        sessionAccess.requireSessionManagementEnabled();
        HttpSession session = httpRequest.getSession(false);
        String userId = sessionAccess.requireSessionUserId(session);
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? null
                : sessionAccess.normalizeSessionId(request.sessionId());
        String memoryId = agentMemoryService.createLongTermMemory(
                userId,
                sessionId,
                request.text().trim(),
                request.memoryType(),
                request.topics()
        );
        return ResponseEntity.ok(new LongTermMemoryCreateResponse(memoryId));
    }

    @DeleteMapping("/memories/{memoryId}")
    public ResponseEntity<Void> deleteMemory(
            @PathVariable String memoryId,
            HttpServletRequest httpRequest
    ) {
        sessionAccess.requireSessionManagementEnabled();
        HttpSession session = httpRequest.getSession(false);
        String userId = sessionAccess.requireSessionUserId(session);
        boolean deleted = agentMemoryService.deleteLongTermMemory(userId, memoryId);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory was not found.");
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/memories")
    public ResponseEntity<LongTermMemoryDeleteResponse> flushMemories(
            HttpServletRequest httpRequest
    ) {
        sessionAccess.requireSessionManagementEnabled();
        HttpSession session = httpRequest.getSession(false);
        String userId = sessionAccess.requireSessionUserId(session);
        return ResponseEntity.ok(new LongTermMemoryDeleteResponse(
                agentMemoryService.deleteLongTermMemories(userId)
        ));
    }

    private LongTermMemoryResponse toLongTermMemoryResponse(MemoryRecordResult memory) {
        return new LongTermMemoryResponse(
                memory.getId(),
                memory.getText(),
                memory.getUserId(),
                memory.getSessionId(),
                memory.getNamespace(),
                memory.getMemoryType() != null ? memory.getMemoryType().getValue() : null,
                memory.getCreatedAt(),
                memory.getUpdatedAt(),
                memory.getLastAccessed(),
                memory.getTopics(),
                memory.getEntities(),
                memory.getDist()
        );
    }
}
