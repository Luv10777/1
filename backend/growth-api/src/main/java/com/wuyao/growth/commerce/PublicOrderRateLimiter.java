package com.wuyao.growth.commerce;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 免登录下单按来源 IP 限流：下单会扣库存，不加限制时一个脚本就能把门店库存锁空。
 * 阈值远高于正常买家的下单频率。Redis 不可用时放行，不让基础设施故障挡住正常下单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicOrderRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local minute = redis.call('INCR', KEYS[1])
            if minute == 1 then redis.call('PEXPIRE', KEYS[1], 60000) end
            if minute > tonumber(ARGV[1]) then return 0 end
            local day = redis.call('INCR', KEYS[2])
            if day == 1 then redis.call('PEXPIRE', KEYS[2], 86400000) end
            if day > tonumber(ARGV[2]) then return 0 end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    @Value("${growth.commerce.orders-per-ip-per-minute:10}")
    private int perMinute;

    @Value("${growth.commerce.orders-per-ip-per-day:100}")
    private int perDay;

    public void check(String ip) {
        String identity = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        Long result;
        try {
            result = redis.execute(SCRIPT, List.of("commerce:order:{" + identity + "}:minute", "commerce:order:{" + identity + "}:day"),
                    Integer.toString(Math.max(1, perMinute)), Integer.toString(Math.max(1, perDay)));
        } catch (RuntimeException e) {
            log.warn("下单限流暂不可用，本次放行: type={}", e.getClass().getSimpleName());
            return;
        }
        if (Long.valueOf(0L).equals(result)) throw BizException.of(ErrorCode.RATE_LIMITED, "下单过于频繁，请稍后再试");
    }
}
