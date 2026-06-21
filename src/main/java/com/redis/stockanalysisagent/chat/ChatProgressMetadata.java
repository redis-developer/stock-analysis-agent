package com.redis.stockanalysisagent.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    public static ChatProgressMetadata empty() {
        return new ChatProgressMetadata(null, null, null, null, null, null, null, null);
    }

    public static ChatProgressMetadata input(String inputPayload) {
        return payload(inputPayload, null);
    }

    public static ChatProgressMetadata payload(String inputPayload, String outputPayload) {
        return new ChatProgressMetadata(
                null,
                hash(inputPayload),
                sizeBytes(inputPayload),
                inputPayload,
                hash(outputPayload),
                sizeBytes(outputPayload),
                outputPayload,
                null
        );
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

    private static String hash(String value) {
        if (value == null) {
            return "";
        }
        try {
            return HEX_FORMAT.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private static Long sizeBytes(String value) {
        return value == null ? null : (long) bytes(value).length;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
