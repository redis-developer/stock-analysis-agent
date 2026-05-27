package com.redis.stockanalysisagent.semanticguardrail;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;

final class JedisClientFactory {

    private JedisClientFactory() {
    }

    static UnifiedJedis create(String host, int port, String username, String password) {
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder();
        if (hasText(username)) {
            config.user(username.trim());
        }
        if (hasText(password)) {
            config.password(password.trim());
        }
        return new UnifiedJedis(new HostAndPort(host, port), config.build());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
