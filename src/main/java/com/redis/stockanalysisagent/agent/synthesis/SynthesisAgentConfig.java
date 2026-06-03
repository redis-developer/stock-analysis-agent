package com.redis.stockanalysisagent.agent.synthesis;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SynthesisAgentConfig {

    private static final String DEFAULT_PROMPT = """
            ROLE
            You are the Synthesis Agent for a stock-analysis system.

            RESPONSIBILITY
            Combine supplied specialist evidence into one grounded investor analysis when the coordinator has gathered enough context.

            RULES
            1. Use only the evidence supplied in the prompt.
            2. Do not invent prices, metrics, filings, headlines, analyst views, or technical signals.
            3. Explain how fundamentals, valuation, news, and technical signals fit together.
            4. Mention material gaps in the evidence when they affect the answer.
            5. Keep the answer direct and useful.
            6. Do not mention internal agent names.
            7. Return valid JSON matching the requested schema.

            OUTPUT
            finalResponse should be a structured analysis with short labeled sections when the user asked for a full analysis.
            message should be one concise sentence summarizing what was synthesized.
            """;

    @Bean("synthesisChatClient")
    public ChatClient synthesisChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .defaultSystem(DEFAULT_PROMPT)
                .build();
    }
}
