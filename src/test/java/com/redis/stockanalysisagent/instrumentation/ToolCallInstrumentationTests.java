package com.redis.stockanalysisagent.instrumentation;

import com.redis.stockanalysisagent.chat.ChatProgressEvent;
import com.redis.stockanalysisagent.chat.WorkflowProgress;
import com.redis.stockanalysisagent.workflow.WorkflowContextHolder;
import com.redis.stockanalysisagent.workflow.events.WorkflowEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ToolCallInstrumentationTests {

    private final WorkflowProgress workflowProgress = new WorkflowProgress(mock(WorkflowEventService.class));
    private final ToolCallInstrumentation instrumentation = new ToolCallInstrumentation(workflowProgress);

    @AfterEach
    void clearWorkflowContext() {
        WorkflowContextHolder.clear();
    }

    @Test
    void wrapsToolObjectAndPublishesSafeProgress() {
        ToolCallback callback = (ToolCallback) instrumentation.callbacks(
                WorkflowProgress.ACTOR_TYPE_SYSTEM,
                WorkflowProgress.ACTOR_SYSTEM,
                new SampleTools()
        )[0];
        List<ChatProgressEvent> events = new ArrayList<>();

        String result = workflowProgress.capture(events::add, () -> callback.call("""
                {"ticker":"aapl","question":"Should I buy Apple with my retirement account?"}
                """));

        assertThat(result).contains("AAPL");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).step().status()).isEqualTo(WorkflowProgress.STATUS_RUNNING);
        assertThat(events.get(1).step().status()).isEqualTo(WorkflowProgress.STATUS_COMPLETED);
        assertThat(events.get(0).step().summary()).contains("getMarketSnapshot");
        assertThat(events.get(0).step().summary()).contains("ticker=AAPL");
        assertThat(events.get(0).step().summary()).contains("question=present");
        assertThat(events.get(0).step().summary()).doesNotContain("retirement");
    }

    @Test
    void wrapsToolObjectWithActorMetadata() {
        ToolCallback callback = (ToolCallback) instrumentation.callbacks(
                WorkflowProgress.ACTOR_TYPE_SUB_AGENT,
                "market_data",
                new SampleTools()
        )[0];
        List<ChatProgressEvent> events = new ArrayList<>();

        workflowProgress.capture(events::add, () -> callback.call("""
                {"ticker":"aapl","question":"Current quote"}
                """));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).step().actorType()).isEqualTo(WorkflowProgress.ACTOR_TYPE_SUB_AGENT);
        assertThat(events.get(0).step().actorName()).isEqualTo("market_data");
        assertThat(events.get(1).step().actorType()).isEqualTo(WorkflowProgress.ACTOR_TYPE_SUB_AGENT);
        assertThat(events.get(1).step().actorName()).isEqualTo("market_data");
    }

    @Test
    void publishesInlineToolPayloadMetadata() {
        WorkflowContextHolder.setWorkflowId("workflow-1");
        ToolCallback callback = (ToolCallback) instrumentation.callbacks(
                WorkflowProgress.ACTOR_TYPE_SUB_AGENT,
                "market_data",
                new SampleTools()
        )[0];
        List<ChatProgressEvent> events = new ArrayList<>();

        workflowProgress.capture(events::add, () -> callback.call("""
                {"ticker":"aapl","question":"Current quote"}
                """));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).step().metadata().toolName()).isEqualTo("getMarketSnapshot");
        assertThat(events.get(0).step().metadata().inputPayload()).contains("Current quote");
        assertThat(events.get(0).step().metadata().inputHash()).hasSize(64);
        assertThat(events.get(0).step().metadata().inputBytes()).isPositive();
        assertThat(events.get(0).step().metadata().outputPayload()).isBlank();
        assertThat(events.get(1).step().metadata().inputPayload()).contains("Current quote");
        assertThat(events.get(1).step().metadata().outputPayload()).contains("AAPL");
        assertThat(events.get(1).step().metadata().outputHash()).hasSize(64);
        assertThat(events.get(1).step().metadata().outputBytes()).isPositive();
    }

    @Test
    void publishesFailedProgressBeforePropagatingException() {
        ToolCallback callback = (ToolCallback) instrumentation.callbacks(
                WorkflowProgress.ACTOR_TYPE_SYSTEM,
                WorkflowProgress.ACTOR_SYSTEM,
                new FailingTools()
        )[0];
        List<ChatProgressEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> workflowProgress.capture(events::add, () -> callback.call("""
                {"ticker":"MSFT","question":"private text"}
                """))).hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).step().status()).isEqualTo(WorkflowProgress.STATUS_RUNNING);
        assertThat(events.get(1).step().status()).isEqualTo(WorkflowProgress.STATUS_FAILED);
        assertThat(events.get(1).step().summary()).contains("IllegalStateException");
        assertThat(events.get(1).step().summary()).doesNotContain("private text");
    }

    static class SampleTools {

        @Tool(description = "Fetch market data.")
        public String getMarketSnapshot(
                @ToolParam(description = "Ticker.")
                String ticker,
                @ToolParam(description = "Question.")
                String question
        ) {
            return ticker.toUpperCase();
        }
    }

    static class FailingTools {

        @Tool(description = "Fail.")
        public String fail(
                @ToolParam(description = "Ticker.")
                String ticker,
                @ToolParam(description = "Question.")
                String question
        ) {
            throw new IllegalStateException("provider returned private text");
        }
    }

}
