package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import com.redis.stockanalysisagent.workflow.WorkflowEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatProgressPublisherTests {

    private final WorkflowEventService workflowEventService = mock(WorkflowEventService.class);
    private final ChatProgressPublisher publisher = new ChatProgressPublisher(workflowEventService);

    @AfterEach
    void clearWorkflowContext() {
        WorkflowContextHolder.clear();
    }

    @Test
    void withoutWorkflowContextOnlyWritesActiveSink() {
        List<ChatProgressEvent> events = new ArrayList<>();

        publisher.capture(events::add, () -> {
            publisher.running(
                    "SEARCH",
                    "Search",
                    ChatProgressPublisher.KIND_SYSTEM,
                    "Searching."
            );
            return null;
        });

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().step().id()).isEqualTo("SEARCH");
        assertThat(events.getFirst().step().actorType()).isEqualTo(ChatProgressPublisher.ACTOR_TYPE_SYSTEM);
        assertThat(events.getFirst().step().actorName()).isEqualTo(ChatProgressPublisher.ACTOR_SYSTEM);
        verify(workflowEventService, never()).appendProgressEvent(any(), any());
    }

    @Test
    void writesExplicitActorMetadata() {
        List<ChatProgressEvent> events = new ArrayList<>();

        publisher.capture(events::add, () -> {
            publisher.running(
                    "COORDINATOR",
                    "Coordinator",
                    ChatProgressPublisher.KIND_AGENT,
                    "Routing.",
                    ChatProgressPublisher.ACTOR_TYPE_COORDINATOR,
                    ChatProgressPublisher.ACTOR_COORDINATOR
            );
            return null;
        });

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().step().actorType()).isEqualTo(ChatProgressPublisher.ACTOR_TYPE_COORDINATOR);
        assertThat(events.getFirst().step().actorName()).isEqualTo(ChatProgressPublisher.ACTOR_COORDINATOR);
    }

    @Test
    void withWorkflowContextWritesActiveSinkAndWorkflowStream() {
        WorkflowContextHolder.setWorkflowId("workflow-1");
        List<ChatProgressEvent> events = new ArrayList<>();

        publisher.capture(events::add, () -> {
            publisher.completed(
                    "SEARCH",
                    "Search",
                    ChatProgressPublisher.KIND_SYSTEM,
                    12,
                    "Searched."
            );
            return null;
        });

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().step().status()).isEqualTo(ChatProgressPublisher.STATUS_COMPLETED);
        verify(workflowEventService).appendProgressEvent("workflow-1", events.getFirst().step());
    }

    @Test
    void withWorkflowContextAndNoActiveSinkStillWritesWorkflowStream() {
        WorkflowContextHolder.setWorkflowId("workflow-1");

        publisher.running(
                "SEARCH",
                "Search",
                ChatProgressPublisher.KIND_SYSTEM,
                "Searching."
        );

        verify(workflowEventService).appendProgressEvent(eq("workflow-1"), any());
    }

    @Test
    void workflowStreamFailureDoesNotStopProgressEvent() {
        WorkflowContextHolder.setWorkflowId("workflow-1");
        doThrow(new IllegalStateException("redis unavailable"))
                .when(workflowEventService).appendProgressEvent(any(), any());
        List<ChatProgressEvent> events = new ArrayList<>();

        assertThatCode(() -> publisher.capture(events::add, () -> {
            publisher.failed(
                    "SEARCH",
                    "Search",
                    ChatProgressPublisher.KIND_SYSTEM,
                    12,
                    "Search failed."
            );
            return null;
        })).doesNotThrowAnyException();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().step().status()).isEqualTo(ChatProgressPublisher.STATUS_FAILED);
    }
}
