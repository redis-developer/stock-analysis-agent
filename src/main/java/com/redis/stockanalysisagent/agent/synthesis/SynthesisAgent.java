package com.redis.stockanalysisagent.agent.synthesis;

import com.redis.stockanalysisagent.agent.TokenUsageSummary;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SynthesisAgent {

    private final ChatClient synthesisChatClient;

    public SynthesisAgent(@Qualifier("synthesisChatClient") ChatClient synthesisChatClient) {
        this.synthesisChatClient = synthesisChatClient;
    }

    public SynthesisResult execute(String question, List<String> tickers, SynthesisEvidence evidence) {
        ResponseEntity<ChatResponse, SynthesisResult> response = synthesisChatClient
                .prompt()
                .user(buildPrompt(question, tickers, evidence))
                .call()
                .responseEntity(SynthesisResult.class);
        TokenUsageSummary tokenUsage = TokenUsageSummary.from(response.response());

        SynthesisResult entity = response.entity();
        if (entity == null
                || entity.getFinalResponse() == null
                || entity.getFinalResponse().isBlank()) {
            throw new IllegalStateException("Synthesis Agent returned an invalid response.");
        }

        entity.setTokenUsage(tokenUsage);
        return entity;
    }

    private String buildPrompt(String question, List<String> tickers, SynthesisEvidence evidence) {
        return """
                USER_QUESTION
                %s

                TICKERS
                %s

                MARKET_DATA
                %s

                FUNDAMENTALS
                %s

                NEWS
                %s

                TECHNICAL_ANALYSIS
                %s

                INSTRUCTIONS
                1. Write the final answer for the user.
                2. Use the supplied evidence as the complete source of truth.
                3. For a full analysis, include business and market setup, fundamentals, valuation, news, technical picture, risks, and final read.
                4. Omit a section only when no useful evidence was supplied for it.
                5. Keep numbers attached to their source context.
                """.formatted(
                question,
                tickers == null || tickers.isEmpty() ? "No explicit ticker list supplied." : String.join(", ", tickers),
                section(evidence.marketData(), "No market data supplied."),
                section(evidence.fundamentals(), "No fundamentals supplied."),
                section(evidence.news(), "No news supplied."),
                section(evidence.technicalAnalysis(), "No technical analysis supplied.")
        );
    }

    private String section(Map<String, Object> values, String emptyValue) {
        if (values == null || values.isEmpty()) {
            return emptyValue;
        }

        StringBuilder section = new StringBuilder();
        values.forEach((ticker, value) -> {
            if (!section.isEmpty()) {
                section.append("\n\n");
            }
            section.append(ticker).append(": ").append(value);
        });
        return section.toString();
    }
}
