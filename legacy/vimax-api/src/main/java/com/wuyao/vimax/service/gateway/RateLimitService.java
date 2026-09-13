package com.wuyao.vimax.service.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * API 限流服务
 *
 * 使用 Redis 实现令牌桶算法
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 检查是否允许调用（令牌桶算法）
     */
    public boolean tryAcquire(String provider, String rateType, int limit, int windowSeconds) {
        String key = String.format("rate_limit:%s:%s", provider, rateType);

        try {
            // 获取当前计数
            String countStr = redisTemplate.opsForValue().get(key);
            int currentCount = countStr != null ? Integer.parseInt(countStr) : 0;

            // 检查是否超限
            if (currentCount >= limit) {
                log.warn("限流触发: provider={}, type={}, current={}, limit={}",
                        provider, rateType, currentCount, limit);
                return false;
            }

            // 增加计数
            Long newCount = redisTemplate.opsForValue().increment(key);

            // 首次设置过期时间
            if (newCount == 1) {
                redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }

            log.debug("限流检查通过: provider={}, type={}, count={}/{}",
                    provider, rateType, newCount, limit);

            return true;

        } catch (Exception e) {
            log.error("限流检查失败，默认允许", e);
            return true;  // 失败时默认允许（降级策略）
        }
    }

    /**
     * 检查每分钟限流
     */
    public boolean checkPerMinuteLimit(String provider, int limitPerMinute) {
        return tryAcquire(provider, "per_minute", limitPerMinute, 60);
    }

    /**
     * 检查每日限流
     */
    public boolean checkPerDayLimit(String provider, int limitPerDay) {
        return tryAcquire(provider, "per_day", limitPerDay, 86400);
    }

    /**
     * 获取剩余配额
     */
    public int getRemainingQuota(String provider, String rateType, int limit) {
        String key = String.format("rate_limit:%s:%s", provider, rateType);

        try {
            String countStr = redisTemplate.opsForValue().get(key);
            int currentCount = countStr != null ? Integer.parseInt(countStr) : 0;
            return Math.max(0, limit - currentCount);
        } catch (Exception e) {
            log.error("获取配额失败", e);
            return limit;
        }
    }
}
