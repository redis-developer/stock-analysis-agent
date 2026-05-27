package com.redis.stockanalysisagent.ratelimiting;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@ConditionalOnProperty(prefix = "app.rate-limiting", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisBucketRateLimitService implements RateLimitService {

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration bucketConfiguration;
    private final int requestsPerMinute;

    public RedisBucketRateLimitService(
            ProxyManager<String> proxyManager,
            RateLimitingProperties properties
    ) {
        this.proxyManager = proxyManager;
        this.requestsPerMinute = properties.requireRequestsPerMinute();
        Duration refillPeriod = properties.requireRefillPeriod();
        this.bucketConfiguration = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute).refillIntervally(requestsPerMinute, refillPeriod))
                .build();
    }

    @Override
    public RateLimitDecision consume(String key) {
        Bucket bucket = proxyManager.getProxy(key, () -> bucketConfiguration);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return new RateLimitDecision(
                probe.isConsumed(),
                probe.getRemainingTokens(),
                probe.isConsumed() ? Duration.ZERO : Duration.ofNanos(probe.getNanosToWaitForRefill())
        );
    }

    @Override
    public RateLimitStatus status(String key) {
        Bucket bucket = proxyManager.getProxy(key, () -> bucketConfiguration);
        return new RateLimitStatus(bucket.getAvailableTokens(), requestsPerMinute);
    }
}
