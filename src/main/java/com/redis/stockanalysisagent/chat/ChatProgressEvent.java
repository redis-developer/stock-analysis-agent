package com.redis.stockanalysisagent.chat;

public record ChatProgressEvent(
        String type,
        ChatProgressStep step,
        ChatResponse response,
        String message
) {

    public static ChatProgressEvent progress(ChatProgressStep step) {
        return new ChatProgressEvent("progress", step, null, null);
    }

    public static ChatProgressEvent finalResponse(ChatResponse response) {
        return new ChatProgressEvent("final", null, response, null);
    }

    public static ChatProgressEvent error(String message) {
        return new ChatProgressEvent("error", null, null, message);
    }
}
