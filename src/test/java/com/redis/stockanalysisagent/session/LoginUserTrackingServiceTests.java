package com.redis.stockanalysisagent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LoginUserTrackingServiceTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PROFILE_TYPE = new TypeReference<>() {
    };

    @Test
    void recordLoginStoresUserAsRedisJson() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-17T10:15:30Z"), ZoneOffset.UTC);
        TestLoginUserTrackingService service = new TestLoginUserTrackingService(clock);

        service.recordLogin(" alice ", " 203.0.113.42 ", " Mozilla/5.0 ", " en-US,en;q=0.9 ", " session-1 ");

        Map<String, Object> profile = service.profile("stock-analysis:users:alice");
        assertThat(profile.get("username")).isEqualTo("alice");
        assertThat(profile.get("firstSeenAt")).isEqualTo("2026-06-17T10:15:30Z");
        assertThat(profile.get("lastSeenAt")).isEqualTo("2026-06-17T10:15:30Z");
        assertThat(profile.get("loginCount")).isEqualTo(1);
        assertThat(profile.get("ipAddresses")).isEqualTo(List.of("203.0.113.42"));
        assertThat(profile.get("lastIpAddress")).isEqualTo("203.0.113.42");
        assertThat(profile.get("userAgents")).isEqualTo(List.of("Mozilla/5.0"));
        assertThat(profile.get("lastUserAgent")).isEqualTo("Mozilla/5.0");
        assertThat(profile.get("acceptLanguages")).isEqualTo(List.of("en-US,en;q=0.9"));
        assertThat(profile.get("lastAcceptLanguage")).isEqualTo("en-US,en;q=0.9");
        assertThat(profile.get("lastSessionId")).isEqualTo("session-1");
    }

    @Test
    void recordLoginPreservesFirstSeenAtAndAppendsUniqueIpAddresses() {
        TestLoginUserTrackingService firstService = new TestLoginUserTrackingService(
                Clock.fixed(Instant.parse("2026-06-17T10:15:30Z"), ZoneOffset.UTC)
        );
        firstService.recordLogin("alice", "203.0.113.42", "Mozilla/5.0", "en-US", "session-1");

        TestLoginUserTrackingService secondService = new TestLoginUserTrackingService(
                firstService.redisJson,
                Clock.fixed(Instant.parse("2026-06-17T11:00:00Z"), ZoneOffset.UTC)
        );
        secondService.recordLogin("alice", "198.51.100.10", "Mozilla/5.0", "en-US", "session-2");

        Map<String, Object> profile = secondService.profile("stock-analysis:users:alice");
        assertThat(profile.get("firstSeenAt")).isEqualTo("2026-06-17T10:15:30Z");
        assertThat(profile.get("lastSeenAt")).isEqualTo("2026-06-17T11:00:00Z");
        assertThat(profile.get("loginCount")).isEqualTo(2);
        assertThat(profile.get("ipAddresses")).isEqualTo(List.of("203.0.113.42", "198.51.100.10"));
        assertThat(profile.get("lastIpAddress")).isEqualTo("198.51.100.10");
        assertThat(profile.get("userAgents")).isEqualTo(List.of("Mozilla/5.0"));
        assertThat(profile.get("lastSessionId")).isEqualTo("session-2");
    }

    @Test
    void recordLoginIgnoresBlankUsernames() {
        TestLoginUserTrackingService service = new TestLoginUserTrackingService(Clock.systemUTC());

        service.recordLogin(" ");

        assertThat(service.redisJson).isEmpty();
    }

    private static final class TestLoginUserTrackingService extends LoginUserTrackingService {

        private final Map<String, String> redisJson;

        private TestLoginUserTrackingService(Clock clock) {
            this(new HashMap<>(), clock);
        }

        private TestLoginUserTrackingService(Map<String, String> redisJson, Clock clock) {
            super(mock(StringRedisTemplate.class), OBJECT_MAPPER, clock);
            this.redisJson = redisJson;
        }

        @Override
        Object executeJsonGet(String key) {
            return redisJson.get(key);
        }

        @Override
        void executeJsonSet(String key, String json) {
            redisJson.put(key, json);
        }

        private Map<String, Object> profile(String key) {
            try {
                return OBJECT_MAPPER.readValue(redisJson.get(key), PROFILE_TYPE);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
