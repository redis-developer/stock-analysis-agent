package com.redis.stockanalysisagent.session;

public record ConversationId(String value, String userId, String sessionId) {

    private static final String SEPARATOR = ":";
    private static final String DEFAULT_SESSION_ID = "default";

    public static ConversationId of(String userId, String sessionId) {
        return new ConversationId(userId + SEPARATOR + sessionId, userId, sessionId);
    }

    public static ConversationId parse(String conversationId) {
        if (conversationId == null) {
            return new ConversationId(null, null, DEFAULT_SESSION_ID);
        }

        int idx = conversationId.indexOf(SEPARATOR);
        if (idx > 0) {
            return new ConversationId(
                    conversationId,
                    conversationId.substring(0, idx),
                    conversationId.substring(idx + SEPARATOR.length())
            );
        }

        return new ConversationId(conversationId, null, conversationId);
    }

    public static String clientSessionId(String userId, String sessionId) {
        String value = sessionId == null ? "" : sessionId.trim();
        if (userId == null) {
            return value;
        }

        ConversationId parsed = parse(value);
        return userId.equals(parsed.userId()) ? parsed.sessionId() : value;
    }
}
