package com.redis.stockanalysisagent.agent.backtest;

import com.redis.stockanalysisagent.chat.ChatProgressPublisher;
import com.redis.stockanalysisagent.instrumentation.ToolCallInstrumentation;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BacktestAgentConfig {

    private static final String DEFAULT_PROMPT = """
            ROLE
            You are the Backtest Agent for a stock analysis system.

            RESPONSIBILITY
            Run deterministic technical signal backtests from historical daily candles.

            RULES
            Use only the backtest tools before returning a completed result.
            Use only historical candles returned by the tools.
            Do not use current market data, current technical analysis, fundamentals, news, memory, or outside knowledge.
            Treat each candle date as the only market data available for that step.
            Use adjusted daily OHLCV unless the user explicitly asks for another adjustment.
            State the strategy rule and assumptions in message.
            Return metrics and assumptions, not investment advice.
            Refuse or error when the requested strategy requires intraday sequencing.
            Return valid JSON matching the requested schema.

            OUTPUT
            finalResponse must contain the backtest report when the request can be completed.
            message must state the strategy rule and assumptions in plain prose.
            """;

    @Bean("backtestChatClient")
    public ChatClient backtestChatClient(
            ChatModel chatModel,
            BacktestTools backtestTools,
            ToolCallInstrumentation toolCallInstrumentation
    ) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .defaultTools(toolCallInstrumentation.callbacks(
                        ChatProgressPublisher.ACTOR_TYPE_SUB_AGENT,
                        "backtest",
                        backtestTools
                ))
                .defaultSystem(DEFAULT_PROMPT)
                .build();
    }
}
