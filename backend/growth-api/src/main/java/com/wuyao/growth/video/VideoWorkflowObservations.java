package com.wuyao.growth.video;

import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.iam.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/** Lazy, short-lived observation snapshots; never called by the creation/get/retry paths. */
@Component
public class VideoWorkflowObservations {
    public static final List<String> ACTIVE_STATES = List.of("QUEUED", "SUBMITTING", "GENERATING", "IMPORTING", "QA");
    private final TenantRepository tenants;
    private final VideoWorkflowRepository workflows;
    private final TenantRateLimiter permits;
    private final TransactionTemplate reads;
    private final long stuckAfterSeconds;
    private final long cacheNanos;
    private Snapshot cached;
    private RuntimeException failure;
    private long sampledAtNanos;
    private boolean sampled;

    public VideoWorkflowObservations(TenantRepository tenants, VideoWorkflowRepository workflows,
            TenantRateLimiter permits, PlatformTransactionManager transactions,
            @Value("${growth.video.health.stuck-after-seconds:${growth.video.max-duration-seconds:7200}}") long stuckAfterSeconds,
            @Value("${growth.video.health.snapshot-cache-seconds:15}") long cacheSeconds) {
        if (stuckAfterSeconds < 1 || cacheSeconds < 0 || cacheSeconds > 3600) {
            throw new IllegalArgumentException("视频观测时长必须为正数，快照缓存须为 0–3600 秒");
        }
        this.tenants = tenants;
        this.workflows = workflows;
        this.permits = permits;
        this.stuckAfterSeconds = stuckAfterSeconds;
        this.cacheNanos = java.time.Duration.ofSeconds(cacheSeconds).toNanos();
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setTimeout(5);
    }

    public long stuckAfterSeconds() { return stuckAfterSeconds; }

    public synchronized Snapshot snapshot() {
        if (!sampled || System.nanoTime() - sampledAtNanos >= cacheNanos) {
            try {
                cached = collect();
                failure = null;
            } catch (RuntimeException e) {
                cached = null;
                failure = e;
            }
            sampledAtNanos = System.nanoTime();
            sampled = true;
        }
        // Failed samples are never represented as a healthy zero or an old successful snapshot.
        if (failure != null) throw failure;
        return cached;
    }

    private Snapshot collect() {
        Instant observedAt = Instant.now();
        Instant cutoff = observedAt.minusSeconds(stuckAfterSeconds);
        long stuck = 0;
        long maxTenantActive = 0;
        boolean permitsAvailable = true;
        Long after = 0L;
        while (true) {
            Long cursor = after;
            var ids = TenantContext.runAs(null, () -> reads.execute(status -> tenants.idsAfter(cursor, PageRequest.of(0, 100))));
            if (ids == null) throw new IllegalStateException("视频观测查询未返回租户列表");
            if (ids.isEmpty()) break;
            for (Long tenantId : ids) {
                // FORCE RLS stays enabled. Set context BEFORE creating a new Hibernate session.
                Long count = TenantContext.runAs(tenantId, () -> reads.execute(status -> workflows.countStuck(tenantId, cutoff)));
                if (count == null) throw new IllegalStateException("视频观测查询未返回计数");
                stuck += count;
                if (permitsAvailable) {
                    long active = permits.getVideoActiveCount(tenantId);
                    if (active < 0) permitsAvailable = false;
                    else maxTenantActive = Math.max(maxTenantActive, active);
                }
            }
            after = ids.getLast();
        }
        return new Snapshot(observedAt, stuck, permitsAvailable ? maxTenantActive : Double.NaN);
    }

    public record Snapshot(Instant observedAt, long stuckWorkflows, double maxTenantActive) {}
}
