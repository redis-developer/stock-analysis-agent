package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import com.redis.stockanalysisagent.cache.ExternalDataAccess;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
public class ChatProgressPublisher {

    public static final String KIND_AGENT = "agent";
    public static final String KIND_SYSTEM = "system";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    private final ThreadLocal<EventSink> activeSink = new ThreadLocal<>();

    public <T> T capture(EventSink sink, Supplier<T> action) {
        activeSink.set(sink);
        try {
            return action.get();
        } finally {
            activeSink.remove();
        }
    }

    public void running(String id, String label, String kind, String summary) {
        emit(step(id, label, kind, STATUS_RUNNING, null, summary, null, List.of()));
    }

    public void completed(String id, String label, String kind, long durationMs, String summary) {
        emit(step(id, label, kind, STATUS_COMPLETED, durationMs, summary, null, List.of()));
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            TokenUsageSummary tokenUsage
    ) {
        emit(step(id, label, kind, STATUS_COMPLETED, durationMs, summary, tokenUsage, List.of()));
    }

    public void completed(
            String id,
            String label,
            String kind,
            long durationMs,
            String summary,
            List<ExternalDataAccess> dataAccesses
    ) {
        emit(step(id, label, kind, STATUS_COMPLETED, durationMs, summary, null, dataAccesses));
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
        emit(step(id, label, kind, STATUS_COMPLETED, durationMs, summary, tokenUsage, dataAccesses));
    }

    public void failed(String id, String label, String kind, long durationMs, String summary) {
        emit(step(id, label, kind, STATUS_FAILED, durationMs, summary, null, List.of()));
    }

    private ChatProgressStep step(
            String id,
            String label,
            String kind,
            String status,
            Long durationMs,
            String summary,
            TokenUsageSummary tokenUsage,
            List<ExternalDataAccess> dataAccesses
    ) {
        return new ChatProgressStep(id, label, kind, status, durationMs, summary, tokenUsage, null, dataAccesses);
    }

    private void emit(ChatProgressStep step) {
        EventSink sink = activeSink.get();
        if (sink != null) {
            sink.accept(ChatProgressEvent.progress(step));
        }
    }

    @FunctionalInterface
    public interface EventSink {
        void accept(ChatProgressEvent event);
    }
}
