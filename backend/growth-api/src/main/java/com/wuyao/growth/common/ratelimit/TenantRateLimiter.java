package com.wuyao.growth.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import com.wuyao.growth.common.quota.TenantQuotaRepository;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 租户级并发限流器
 * 防止单个租户占用过多图片生成资源
 */
@Slf4j
@Component
public class TenantRateLimiter {
    private static final long PERMIT_TTL_MILLIS = 2 * 60 * 60 * 1000L;
    private static final String IMAGE_GLOBAL_KEY = "image:global:active";
    private static final String VIDEO_GLOBAL_KEY = "video:global:active";
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local tenantLimit = tonumber(ARGV[1])
            local globalLimit = tonumber(ARGV[2])
            local ttlMillis = tonumber(ARGV[3])
            local tenantCurrent = tonumber(redis.call('GET', KEYS[1]) or '0')
            local globalCurrent = tonumber(redis.call('GET', KEYS[2]) or '0')
            if tenantCurrent >= tenantLimit or globalCurrent >= globalLimit then
              return 0
            end
            redis.call('INCR', KEYS[1])
            redis.call('INCR', KEYS[2])
            redis.call('PEXPIRE', KEYS[1], ttlMillis)
            redis.call('PEXPIRE', KEYS[2], ttlMillis)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local tenantCurrent = tonumber(redis.call('GET', KEYS[1]) or '0')
            if tenantCurrent <= 1 then
              redis.call('DEL', KEYS[1])
            else
              redis.call('DECR', KEYS[1])
            end
            local globalCurrent = tonumber(redis.call('GET', KEYS[2]) or '0')
            if globalCurrent <= 1 then
              redis.call('DEL', KEYS[2])
            else
              redis.call('DECR', KEYS[2])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final TenantQuotaRepository quotas;

    @Autowired
    public TenantRateLimiter(StringRedisTemplate redis, TenantQuotaRepository quotas) {
        this.redis = redis;
        this.quotas = quotas;
    }

    /** Kept for focused unit tests that do not start the JPA context. */
    public TenantRateLimiter(StringRedisTemplate redis) {
        this(redis, null);
    }

    /**
     * 尝试获取图片生成许可
     * @param tenantId 租户 ID
     * @param maxConcurrent 租户最大并发数
     * @param globalMaxConcurrent 全局最大并发数
     * @return true 获取成功, false 已达上限
     */
    public boolean tryAcquireImageGeneration(Long tenantId, int maxConcurrent, int globalMaxConcurrent) {
        int tenantLimit = maxConcurrent;
        if (quotas != null) {
            try {
                tenantLimit = quotas.findById(tenantId)
                    .map(q -> q.getConcurrentLimit() > 0 ? q.getConcurrentLimit() : maxConcurrent)
                    .orElse(maxConcurrent);
            } catch (RuntimeException e) {
                log.error("读取租户图片并发配额失败，拒绝本次创建: tenant={}", tenantId, e);
                throw BizException.of(ErrorCode.STORAGE_UNAVAILABLE, "图片并发配额暂时不可用，请稍后重试");
            }
        }
        if (tenantLimit < 1 || globalMaxConcurrent < 1) return false;
        return tryAcquire(tenantId, tenantLimit, globalMaxConcurrent, tenantKey(tenantId), IMAGE_GLOBAL_KEY,
                PERMIT_TTL_MILLIS, "图片");
    }

    /**
     * Kept for callers that only need a tenant limit (for example focused tests).
     */
    public boolean tryAcquireImageGeneration(Long tenantId, int maxConcurrent) {
        return tryAcquireImageGeneration(tenantId, maxConcurrent, Integer.MAX_VALUE);
    }

    /**
     * 释放租户和全局图片生成许可。
     * The decrement and cleanup happen in one Redis script so a concurrent acquire
     * cannot be erased by a later DEL.
     */
    public void releaseImageGeneration(Long tenantId) {
        release(tenantKey(tenantId), IMAGE_GLOBAL_KEY, tenantId, "图片");
    }

    /**
     * Video jobs use a separate counter namespace. Image and video workloads
     * therefore cannot consume one another's permits.
     */
    public boolean tryAcquireVideoGeneration(Long tenantId, int maxConcurrent, int globalMaxConcurrent,
                                             long ttlSeconds) {
        return tryAcquire(tenantId, maxConcurrent, globalMaxConcurrent, videoTenantKey(tenantId),
                VIDEO_GLOBAL_KEY, Math.max(1L, ttlSeconds) * 1000L, "视频");
    }

    public void releaseVideoGeneration(Long tenantId) {
        release(videoTenantKey(tenantId), VIDEO_GLOBAL_KEY, tenantId, "视频");
    }

    /**
     * 获取租户当前活跃任务数
     */
    public long getActiveCount(Long tenantId) {
        return getCount(tenantKey(tenantId));
    }

    public long getGlobalActiveCount() {
        return getCount(IMAGE_GLOBAL_KEY);
    }

    public long getVideoActiveCount(Long tenantId) {
        return getCount(videoTenantKey(tenantId));
    }

    public long getVideoGlobalActiveCount() {
        return getCount(VIDEO_GLOBAL_KEY);
    }

    private long getCount(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException e) {
            log.warn("读取图片并发数失败: key={}", key, e);
            return -1;
        }
    }

    private String tenantKey(Long tenantId) {
        return "tenant:" + tenantId + ":image:active";
    }

    private String videoTenantKey(Long tenantId) {
        return "tenant:" + tenantId + ":video:active";
    }

    private boolean tryAcquire(Long tenantId, int tenantLimit, int globalLimit, String tenantKey,
                               String globalKey, long ttlMillis, String label) {
        if (tenantLimit < 1 || globalLimit < 1) return false;
        try {
            Long acquired = redis.execute(ACQUIRE_SCRIPT, List.of(tenantKey, globalKey),
                    Integer.toString(tenantLimit), Integer.toString(globalLimit), Long.toString(ttlMillis));
            if (!Long.valueOf(1L).equals(acquired)) {
                log.warn("{}生成并发数已达上限: tenant={}/{} global={}", label, tenantId, tenantLimit, globalLimit);
                return false;
            }
            log.debug("租户 {} 获取{}生成许可: tenantLimit={} globalLimit={}", tenantId, label, tenantLimit, globalLimit);
            return true;
        } catch (RuntimeException e) {
            log.error("Redis {}并发限流不可用，拒绝本次创建: tenant={}", label, tenantId, e);
            throw BizException.of(ErrorCode.STORAGE_UNAVAILABLE, label + "并发控制暂时不可用，请稍后重试");
        }
    }

    private void release(String tenantKey, String globalKey, Long tenantId, String label) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(tenantKey, globalKey));
            log.debug("租户 {} 释放{}生成许可", tenantId, label);
        } catch (RuntimeException e) {
            log.error("释放租户{}生成并发许可失败，等待 TTL 兜底: tenant={}", label, tenantId, e);
        }
    }
}
