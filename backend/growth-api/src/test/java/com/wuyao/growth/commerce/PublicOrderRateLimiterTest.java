package com.wuyao.growth.commerce;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicOrderRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PublicOrderRateLimiter limiter = new PublicOrderRateLimiter(redis);

    PublicOrderRateLimiterTest() {
        ReflectionTestUtils.setField(limiter, "perMinute", 10);
        ReflectionTestUtils.setField(limiter, "perDay", 100);
    }

    @SuppressWarnings("unchecked")
    private void redisReturns(Object value) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(value);
    }

    @Test
    void allowsOrdersWithinTheLimitAndCountsPerAddress() {
        redisReturns(1L);
        assertThatCode(() -> limiter.check("203.0.113.9")).doesNotThrowAnyException();
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("commerce:order:{203.0.113.9}:minute", "commerce:order:{203.0.113.9}:day")), eq("10"), eq("100"));
    }

    @Test
    void refusesOnceTheAddressIsOverTheLimit() {
        redisReturns(0L);
        assertThatThrownBy(() -> limiter.check("203.0.113.9"))
                .isInstanceOfSatisfying(BizException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnavailableRedisDoesNotBlockOrdering() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenThrow(new RedisConnectionFailureException("down"));
        assertThatCode(() -> limiter.check(null)).doesNotThrowAnyException();
    }
}
