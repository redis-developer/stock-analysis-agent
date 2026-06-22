package com.redis.stockanalysisagent.session;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionIndexServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-22T12:18:35.910Z"), ZoneOffset.UTC);

    @Test
    void recordSessionStartedWritesUserSessionSortedSet() {
        RedisHarness redis = redisHarness();
        ChatSessionIndexService service = new ChatSessionIndexService(redis.redisTemplate(), CLOCK);

        service.recordSessionStarted("alice", "session-1");

        verify(redis.zSetOperations()).add(
                ChatSessionIndexService.sessionsKey("alice"),
                "session-1",
                1782130715910.0
        );
    }

    @Test
    void listSessionsReadsMostRecentFirst() {
        RedisHarness redis = redisHarness();
        when(redis.zSetOperations().reverseRange(ChatSessionIndexService.sessionsKey("alice"), 0, -1))
                .thenReturn(new LinkedHashSet<>(List.of("session-3", "session-2", "session-1")));
        ChatSessionIndexService service = new ChatSessionIndexService(redis.redisTemplate(), CLOCK);

        assertThat(service.listSessions("alice")).containsExactly("session-3", "session-2", "session-1");
    }

    @Test
    void removeSessionDeletesSortedSetMember() {
        RedisHarness redis = redisHarness();
        ChatSessionIndexService service = new ChatSessionIndexService(redis.redisTemplate(), CLOCK);

        service.removeSession("alice", "session-1");

        verify(redis.zSetOperations()).remove(ChatSessionIndexService.sessionsKey("alice"), "session-1");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RedisHarness redisHarness() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        return new RedisHarness(redisTemplate, zSetOperations);
    }

    private record RedisHarness(
            StringRedisTemplate redisTemplate,
            ZSetOperations<String, String> zSetOperations
    ) {
    }
}
