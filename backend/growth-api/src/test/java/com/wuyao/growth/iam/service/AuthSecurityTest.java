package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuthSecurityTest {
    final UserRepository users = mock(UserRepository.class);
    final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
    final SmsCodeRepository codes = mock(SmsCodeRepository.class);
    final SmsSender sender = mock(SmsSender.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final JwtService jwt = new JwtService("test-only-random-signing-secret-0123456789abcdef", Duration.ofHours(1), Duration.ofDays(30));
    final AuthService auth = new AuthService(mock(TenantRepository.class), users, refresh, codes, sender, jwt,
            mock(LoginCodeVerifier.class), mock(PasswordLoginVerifier.class), mock(TransactionTemplate.class), redis);

    @Test void logoutAdvancesVersionAndRevokesAllRefreshTokens() {
        User user = new User(); user.setId(1L); user.setTenantId(2L); user.setTokenVersion(9);
        when(users.findForUpdate(1L)).thenReturn(Optional.of(user));
        auth.logout(1L);
        assertThat(user.getTokenVersion()).isEqualTo(10);
        verify(refresh).revokeAllForUser(1L);
    }
    @Test void disabledOrRevokedUsersCannotRefresh() {
        User user = new User(); user.setId(1L); user.setTenantId(2L); user.setTokenVersion(9);
        when(users.findForUpdate(1L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> auth.refresh(jwt.issueRefreshToken(1L, 2L, null, 8), null, null, null))
                .isInstanceOf(BizException.class);
        user.setStatus("DISABLED");
        assertThatThrownBy(() -> auth.refresh(jwt.issueRefreshToken(1L, 2L, null, 9), null, null, null))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(refresh);
    }
    @Test void ipLimitsAndRedisFailureRejectBeforeSendingOrWritingCodes() {
        for (Long result : new Long[]{0L, 1L, null}) {
            when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(result);
            assertThatThrownBy(() -> auth.sendCode("13800000001", "203.0.113.1")).isInstanceOf(BizException.class);
        }
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        assertThatThrownBy(() -> auth.sendCode("13800000001", "203.0.113.1")).isInstanceOf(BizException.class);
        verifyNoInteractions(codes, sender);
    }
    @Test void allowedRequestStillAppliesPhoneLimitsAndStoresOnlyHash() {
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(2L);
        ReflectionTestUtils.setField(auth, "perMinute", 1);
        ReflectionTestUtils.setField(auth, "perDay", 10);
        ReflectionTestUtils.setField(auth, "codeLength", 6);
        ReflectionTestUtils.setField(auth, "codeTtl", Duration.ofMinutes(5));
        auth.sendCode("13800000001", "203.0.113.1");
        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sender).sendLoginCode(eq("13800000001"), sent.capture());
        var record = org.mockito.ArgumentCaptor.forClass(com.wuyao.growth.iam.entity.SmsCode.class);
        verify(codes).save(record.capture());
        assertThat(record.getValue().getCodeHash()).hasSize(64).isNotEqualTo(sent.getValue());
    }
}
