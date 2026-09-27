package com.wuyao.growth.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Redis-backed image model rate limiter.
 * A fixed one-second window is shared by every worker instance.
 */
@Slf4j
@Component
public class ImageApiRateLimiter {
    private static final DefaultRedisScript<Long> WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            if count <= tonumber(ARGV[1]) then return 1 end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int limitPerSecond;
    private final long timeoutMillis;

    public ImageApiRateLimiter(
            StringRedisTemplate redis,
            @Value("${growth.image.api-rate-limit:100}") int limitPerSecond,
            @Value("${growth.image.api-timeout:10}") int timeoutSeconds) {
        this.redis = redis;
        this.limitPerSecond = Math.max(1, limitPerSecond);
        this.timeoutMillis = Math.max(1, timeoutSeconds) * 1000L;
        log.info("图片 API 分布式限流器初始化: {} req/s, 超时 {}s", limitPerSecond, timeoutSeconds);
    }

    public void waitForPermission() {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String key = "image:api:rate:" + Instant.now().getEpochSecond();
            try {
                Long allowed = redis.execute(WINDOW_SCRIPT, List.of(key),
                        Integer.toString(limitPerSecond), "2000");
                if (Long.valueOf(1L).equals(allowed)) return;
            } catch (RuntimeException e) {
                log.error("Redis 图片 API 分布式限流不可用，拒绝模型调用", e);
                throw new IllegalStateException("图片模型限流服务暂时不可用，请稍后重试", e);
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("图片模型限流等待被中断", e);
            }
        }
        throw new IllegalStateException("图片模型请求过于频繁，请稍后重试");
    }

    public int getAvailablePermissions() {
        try {
            String key = "image:api:rate:" + Instant.now().getEpochSecond();
            String value = redis.opsForValue().get(key);
            return Math.max(0, limitPerSecond - (value == null ? 0 : Integer.parseInt(value)));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
