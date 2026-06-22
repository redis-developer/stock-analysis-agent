package com.redis.stockanalysisagent.memory.controller;

import com.redis.agentmemory.models.longtermemory.MemoryRecordResult;
import com.redis.agentmemory.models.longtermemory.MemoryType;
import com.redis.stockanalysisagent.memory.controller.vo.LongTermMemoriesResponse;
import com.redis.stockanalysisagent.memory.controller.vo.LongTermMemoryCreateRequest;
import com.redis.stockanalysisagent.memory.controller.vo.LongTermMemoryCreateResponse;
import com.redis.stockanalysisagent.memory.controller.vo.LongTermMemoryDeleteResponse;
import com.redis.stockanalysisagent.memory.service.AgentMemoryService;
import com.redis.stockanalysisagent.session.ChatSessionAccess;
import com.redis.stockanalysisagent.session.controller.ChatSessionController;
import com.redis.stockanalysisagent.session.ChatSessionService;
import com.redis.stockanalysisagent.session.controller.vo.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryControllerTests {

    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final ChatSessionAccess sessionAccess = new ChatSessionAccess(true);
    private final ChatSessionController sessionController = new ChatSessionController(chatSessionService, sessionAccess);
    private final ChatMemoryController controller = new ChatMemoryController(agentMemoryService, sessionAccess);

    @Test
    void memoriesEndpointReturnsCurrentUsersLongTermMemories() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        MemoryRecordResult memory = new MemoryRecordResult();
        memory.setId("memory-1");
        memory.setText("Alice owns NVDA.");
        memory.setUserId("alice");
        memory.setSessionId("session-1");
        memory.setNamespace("test");
        memory.setMemoryType(MemoryType.SEMANTIC);
        memory.setCreatedAt(Instant.parse("2026-05-26T10:00:00Z"));
        memory.setUpdatedAt(Instant.parse("2026-05-26T10:05:00Z"));
        memory.setLastAccessed(Instant.parse("2026-05-26T10:06:00Z"));
        memory.setTopics(List.of("stocks"));
        when(agentMemoryService.listLongTermMemories("alice")).thenReturn(List.of(memory));

        LongTermMemoriesResponse response = controller.memories(request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.memories()).hasSize(1);
        assertThat(response.memories().getFirst().id()).isEqualTo("memory-1");
        assertThat(response.memories().getFirst().text()).isEqualTo("Alice owns NVDA.");
        assertThat(response.memories().getFirst().memoryType()).isEqualTo("semantic");
    }

    @Test
    void deleteMemoryUsesCurrentSessionUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        when(agentMemoryService.deleteLongTermMemory("alice", "memory-1")).thenReturn(true);

        controller.deleteMemory("memory-1", request);

        verify(agentMemoryService).deleteLongTermMemory("alice", "memory-1");
    }

    @Test
    void createMemoryUsesCurrentSessionUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        when(agentMemoryService.createLongTermMemory(
                "alice",
                "session-1",
                "Alice owns NVDA.",
                "semantic",
                List.of("stocks", "nvda")
        )).thenReturn("memory-1");

        LongTermMemoryCreateResponse response = controller.createMemory(
                new LongTermMemoryCreateRequest(
                        " Alice owns NVDA. ",
                        "semantic",
                        List.of("stocks", "nvda"),
                        "session-1"
                ),
                request
        ).getBody();

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("memory-1");
        verify(agentMemoryService).createLongTermMemory(
                "alice",
                "session-1",
                "Alice owns NVDA.",
                "semantic",
                List.of("stocks", "nvda")
        );
    }

    @Test
    void flushMemoriesReturnsDeletedCount() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionController.login(new LoginRequest("alice", 7, null, null, null, null, null), request);
        when(agentMemoryService.deleteLongTermMemories("alice")).thenReturn(3);

        LongTermMemoryDeleteResponse response = controller.flushMemories(request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.deleted()).isEqualTo(3);
    }
}
