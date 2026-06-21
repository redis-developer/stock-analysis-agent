package com.redis.stockanalysisagent.chat;

import com.redis.stockanalysisagent.session.dto.ChatSessionMetadata;

public record ChatProgressEvent(
        String type,
        ChatProgressStep step,
        ChatResponse response,
        String message,
        ChatSessionMetadata metadata
) {

    public static ChatProgressEvent progress(ChatProgressStep step) {
        return new ChatProgressEvent("progress", step, null, null, null);
    }

    public static ChatProgressEvent workflow(ChatSessionMetadata metadata) {
        return new ChatProgressEvent("workflow", null, null, null, metadata);
    }

    public static ChatProgressEvent finalResponse(ChatResponse response) {
        return new ChatProgressEvent("final", null, response, null, null);
    }

    public static ChatProgressEvent error(String message) {
        return new ChatProgressEvent("error", null, null, message, null);
    }
}
