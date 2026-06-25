package com.redis.stockanalysisagent.workflow.execution;

import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class WorkflowLeaseService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLeaseService.class);

    public static final Duration DEFAULT_WORKFLOW_LEASE = Duration.ofSeconds(15);
    public static final Duration DEFAULT_WORKFLOW_LEASE_RENEWAL_INTERVAL = Duration.ofSeconds(5);

    private static final RedisScript<Long> RENEW_LEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);
    private static final RedisScript<Long> RELEASE_LEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String workerId;
    private final Duration workflowLease;
    private final Duration workflowLeaseRenewalInterval;
    private final ScheduledExecutorService leaseRenewalExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "workflow-lease-renewal");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public WorkflowLeaseService(
            StringRedisTemplate redisTemplate,
            @Value("${stock-analysis.workflow.lease:15s}") Duration workflowLease,
            @Value("${stock-analysis.workflow.lease-renewal-interval:5s}") Duration workflowLeaseRenewalInterval
    ) {
        this(redisTemplate, () -> UUID.randomUUID().toString(), workflowLease, workflowLeaseRenewalInterval);
    }

    public WorkflowLeaseService(
            StringRedisTemplate redisTemplate,
            Supplier<String> workerIdSupplier,
            Duration workflowLease,
            Duration workflowLeaseRenewalInterval
    ) {
        this.redisTemplate = redisTemplate;
        this.workerId = workerIdSupplier.get();
        this.workflowLease = workflowLease == null ? DEFAULT_WORKFLOW_LEASE : workflowLease;
        this.workflowLeaseRenewalInterval = workflowLeaseRenewalInterval == null
                ? DEFAULT_WORKFLOW_LEASE_RENEWAL_INTERVAL
                : workflowLeaseRenewalInterval;
    }

    public String workerId() {
        return workerId;
    }

    public void startLease(RedisOperations operations, String workflowId) {
        operations.opsForValue().setIfAbsent(leaseKey(workflowId), workerId, workflowLease);
    }

    public boolean acquire(String workflowId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(leaseKey(workflowId), workerId, workflowLease);
        if (Boolean.TRUE.equals(acquired)) {
            log.info("workflow_lease_acquired workflowId={} workerId={}", workflowId, workerId);
            return true;
        }
        log.debug("workflow_lease_busy workflowId={} workerId={}", workflowId, workerId);
        return false;
    }

    public boolean isLeaseHolder(String workflowId) {
        return workerId.equals(redisTemplate.opsForValue().get(leaseKey(workflowId)));
    }

    public Lease renewUntilClosed(String workflowId, WorkflowStatus status) {
        if (workflowId == null || workflowId.isBlank() || (status != WorkflowStatus.RUNNING
                && status != WorkflowStatus.RECOVERING)) {
            return Lease.noop();
        }
        AtomicReference<ScheduledFuture<?>> renewal = new AtomicReference<>();
        ScheduledFuture<?> future = leaseRenewalExecutor.scheduleAtFixedRate(
                () -> {
                    if (!renewSafely(workflowId)) {
                        ScheduledFuture<?> scheduled = renewal.get();
                        if (scheduled != null) {
                            scheduled.cancel(false);
                        }
                    }
                },
                workflowLeaseRenewalInterval.toMillis(),
                workflowLeaseRenewalInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
        renewal.set(future);
        return new Lease(future, () -> release(workflowId));
    }

    public boolean renew(String workflowId) {
        Long result = redisTemplate.execute(
                RENEW_LEASE_SCRIPT,
                List.of(leaseKey(workflowId)),
                workerId,
                Long.toString(workflowLease.toMillis())
        );
        if (result != null && result == 1L) {
            log.debug("workflow_lease_renewed workflowId={} workerId={}", workflowId, workerId);
            return true;
        }
        log.warn("workflow_lease_renewal_rejected workflowId={} workerId={} result={}", workflowId, workerId, result);
        return false;
    }

    public void release(String workflowId) {
        Long result = redisTemplate.execute(RELEASE_LEASE_SCRIPT, List.of(leaseKey(workflowId)), workerId);
        if (result != null && result > 0L) {
            log.debug("workflow_lease_released workflowId={} workerId={}", workflowId, workerId);
        }
    }

    public static String leaseKey(String workflowId) {
        return WorkflowService.workflowKey(workflowId) + ":lease";
    }

    @PreDestroy
    void stopLeaseRenewalExecutor() {
        leaseRenewalExecutor.shutdownNow();
    }

    private boolean renewSafely(String workflowId) {
        try {
            if (!renew(workflowId)) {
                log.warn("Stopped renewing workflow {} because ownership changed.", workflowId);
                return false;
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to renew workflow {} lease: {}", workflowId, ex.getMessage());
        }
        return true;
    }

    public static final class Lease implements AutoCloseable {

        private final ScheduledFuture<?> future;
        private final Runnable release;
        private boolean closed;

        private Lease(ScheduledFuture<?> future, Runnable release) {
            this.future = future;
            this.release = release;
        }

        public static Lease noop() {
            return new Lease(null, () -> {
            });
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (future != null) {
                future.cancel(false);
            }
            release.run();
        }
    }
}
