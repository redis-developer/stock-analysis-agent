package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataAccess;
import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import com.redis.stockanalysisagent.workflow.WorkflowEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
public class ChatProgressPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatProgressPublisher.class);

    public static final String KIND_AGENT = "agent";
    public static final String KIND_SYSTEM = "system";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String ACTOR_TYPE_COORDINATOR = "coordinator";
    public static final String ACTOR_TYPE_SUB_AGENT = "sub_agent";
    public static final String ACTOR_TYPE_SYSTEM = "system";
    public static final String ACTOR_COORDINATOR = "coordinator";
    public static final String ACTOR_SYSTEM = "system";

    private final ThreadLocal<EventSink> activeSink = new ThreadLocal<>();
    private final WorkflowEventService workflowEventService;

    public ChatProgressPublisher(WorkflowEventService workflowEventService) {
        this.workflowEventService = workflowEventService;
    }

    public <T> T capture(EventSink sink, Supplier<T> action) {
        activeSink.set(sink);
        try {
            return action.get();
        } finally {
            activeSink.remove();
        }
    }

    public void running(String id, String label, String kind, String summary) {
        running(id, label, kind, summary, defaultActorType(kind), defaultActorName(kind));
    }

    public void running(String id, String label, String kind, String summary, String actorType, String actorName) {
        running(id, label, kind, summary, actorType, actorName, ChatProgressMetadata.empty());
    }

    public void running(
            String id,
            String label,
            String kind,
            String summary,
            String actorType,
            String actorName,
            ChatProgressMetadata metadata
    ) {
        emit(step(id, label, kind, STATUS_RUNNING, null, summary, null, List.of(), actorType, actorName, metadata));
    }

    public void completed(String id, String label, String kind, long durationMs, String summary) {
        completed(id, label, kind, durationMs, summary, defaultActorType(kind), defaultActorName(kind));
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            String actorType,
            String actorName
    ) {
        completed(id, label, kind, durationMs, summary, actorType, actorName, ChatProgressMetadata.empty());
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            String actorType,
            String actorName,
            ChatProgressMetadata metadata
    ) {
        emit(step(id, label, kind, STATUS_COMPLETED, durationMs, summary, null, List.of(), actorType, actorName, metadata));
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage
    ) {
        completed(id, label, kind, durationMs, summary, tokenUsage, defaultActorType(kind), defaultActorName(kind));
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage,
            String actorType,
            String actorName
    ) {
        completed(id, label, kind, durationMs, summary, tokenUsage, actorType, actorName, ChatProgressMetadata.empty());
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage,
            String actorType,
            String actorName,
            ChatProgressMetadata metadata
    ) {
        emit(step(
                id,
                label,
                kind,
                STATUS_COMPLETED,
                durationMs,
                summary,
                tokenUsage,
                List.of(),
                actorType,
                actorName,
                metadata
        ));
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            List<ExternalDataAccess> dataAccesses
    ) {
        completed(id, label, kind, durationMs, summary, dataAccesses, defaultActorType(kind), defaultActorName(kind));
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            List<ExternalDataAccess> dataAccesses,
            String actorType,
            String actorName
    ) {
        completed(id, label, kind, durationMs, summary, dataAccesses, actorType, actorName, ChatProgressMetadata.empty());
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            List<ExternalDataAccess> dataAccesses,
            String actorType,
            String actorName,
            ChatProgressMetadata metadata
    ) {
        emit(step(
                id,
                label,
                kind,
                STATUS_COMPLETED,
                durationMs,
                summary,
                null,
                dataAccesses,
                actorType,
                actorName,
                metadata
        ));
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage,
            List<ExternalDataAccess> dataAccesses
    ) {
        completed(
                id,
                label,
                kind,
                durationMs,
                summary,
                tokenUsage,
                dataAccesses,
                defaultActorType(kind),
                defaultActorName(kind)
        );
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage,
            List<ExternalDataAccess> dataAccesses,
            String actorType,
            String actorName
    ) {
        completed(id, label, kind, durationMs, summary, tokenUsage, dataAccesses, actorType, actorName, ChatProgressMetadata.empty());
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage,
            List<ExternalDataAccess> dataAccesses,
            String actorType,
            String actorName,
            ChatProgressMetadata metadata
    ) {
        emit(step(
                id,
                label,
                kind,
                STATUS_COMPLETED,
                durationMs,
                summary,
                tokenUsage,
                dataAccesses,
                actorType,
                actorName,
                metadata
        ));
    }

    public void failed(String id, String label, String kind, long durationMs, String summary) {
        failed(id, label, kind, durationMs, summary, defaultActorType(kind), defaultActorName(kind));
    }

    public void failed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            String actorType,
            String actorName
    ) {
        failed(id, label, kind, durationMs, summary, actorType, actorName, ChatProgressMetadata.empty());
    }

    public void failed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            String actorType,
            String actorName,
            ChatProgressMetadata metadata
    ) {
        emit(step(id, label, kind, STATUS_FAILED, durationMs, summary, null, List.of(), actorType, actorName, metadata));
    }

    public void workflow(ChatSessionMetadata metadata) {
        EventSink sink = activeSink.get();
        if (sink != null) {
            sink.accept(ChatProgressEvent.workflow(metadata));
        }
    }

    private ChatProgressStep step(
            String id,
            String label,
            String kind,
            String status,
            Long durationMs,
            String summary,
            TokenUsageSummary tokenUsage,
            List<ExternalDataAccess> dataAccesses,
            String actorType,
            String actorName,
            ChatProgressMetadata metadata
    ) {
        return new ChatProgressStep(
                id,
                label,
                kind,
                status,
                durationMs,
                summary,
                tokenUsage,
                null,
                dataAccesses,
                actorType,
                actorName,
                metadata
        );
    }

    private String defaultActorType(String kind) {
        return KIND_AGENT.equals(kind) ? KIND_AGENT : ACTOR_TYPE_SYSTEM;
    }

    private String defaultActorName(String kind) {
        return KIND_AGENT.equals(kind) ? "" : ACTOR_SYSTEM;
    }

    private void emit(ChatProgressStep step) {
        EventSink sink = activeSink.get();
        if (sink != null) {
            sink.accept(ChatProgressEvent.progress(step));
        }
        WorkflowContextHolder.workflowId().ifPresent(workflowId -> appendWorkflowEvent(workflowId, step));
    }

    private void appendWorkflowEvent(String workflowId, ChatProgressStep step) {
        try {
            workflowEventService.appendProgressEvent(workflowId, step);
        } catch (RuntimeException ex) {
            log.warn("Failed to append workflow progress event for workflow {}", workflowId, ex);
        }
    }

    @FunctionalInterface
    public interface EventSink {
        void accept(ChatProgressEvent event);
    }
}
