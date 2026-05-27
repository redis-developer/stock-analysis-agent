package com.redis.stockanalysisagent.ratelimiting;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(RateLimitingProperties.class)
@ConditionalOnProperty(prefix = "app.rate-limiting", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitingConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitingRedisClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.username:}") String username,
            @Value("${spring.data.redis.password:}") String password
    ) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(port);

        if (StringUtils.hasText(username)) {
            builder.withAuthentication(username, password.toCharArray());
        } else if (StringUtils.hasText(password)) {
            builder.withPassword(password.toCharArray());
        }

        return RedisClient.create(builder.build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> rateLimitingRedisConnection(RedisClient rateLimitingRedisClient) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return rateLimitingRedisClient.connect(codec);
    }

    @Bean
    public ProxyManager<String> rateLimitingProxyManager(
            StatefulRedisConnection<String, byte[]> rateLimitingRedisConnection,
            RateLimitingProperties properties
    ) {
        return Bucket4jLettuce.casBasedBuilder(rateLimitingRedisConnection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                        properties.requireRefillPeriod()
                ))
                .build();
    }
}
