package com.redis.stockanalysisagent.chat;

public record ChatProgressMetadata(
        String toolName,
        String inputHash,
        Long inputBytes,
        String inputPayload,
        String outputHash,
        Long outputBytes,
        String outputPayload,
        String errorType
) {

    public static ChatProgressMetadata empty() {
        return new ChatProgressMetadata(null, null, null, null, null, null, null, null);
    }

    public ChatProgressMetadata {
        toolName = clean(toolName);
        inputHash = clean(inputHash);
        inputPayload = payload(inputPayload);
        outputHash = clean(outputHash);
        outputPayload = payload(outputPayload);
        errorType = clean(errorType);
    }

    public boolean hasFields() {
        return !toolName.isBlank()
                || !inputHash.isBlank()
                || inputBytes != null
                || !inputPayload.isBlank()
                || !outputHash.isBlank()
                || outputBytes != null
                || !outputPayload.isBlank()
                || !errorType.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String payload(String value) {
        return value == null ? "" : value;
    }
}
