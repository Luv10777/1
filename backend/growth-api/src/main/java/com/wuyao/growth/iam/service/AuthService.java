package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.security.JwtService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.dto.AuthDtos;
import com.wuyao.growth.iam.entity.RefreshToken;
import com.wuyao.growth.iam.entity.SmsCode;
import com.wuyao.growth.iam.entity.Tenant;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.RefreshTokenRepository;
import com.wuyao.growth.iam.repository.SmsCodeRepository;
import com.wuyao.growth.iam.repository.TenantRepository;
import com.wuyao.growth.iam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 手机验证码登录（登录即注册）。
 *
 * 三条不能省的：验证码只存哈希、发送要限流、refresh token 要轮换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SmsCodeRepository smsCodeRepository;
    private final SmsSender smsSender;
    private final JwtService jwtService;
    private final LoginCodeVerifier codeVerifier;
    private final TransactionTemplate transactions;

    @Value("${growth.sms.code-length:6}")
    private int codeLength;

    @Value("${growth.sms.code-ttl:5m}")
    private Duration codeTtl;

    @Value("${growth.sms.per-phone-per-minute:1}")
    private int perMinute;

    @Value("${growth.sms.per-phone-per-day:10}")
    private int perDay;

    @Transactional
    public AuthDtos.SendCodeResult sendCode(String phone, String ip) {
        // 同一手机号的限流检查和写入必须串行，防止并发请求同时通过计数检查。
        smsCodeRepository.lockPhone(phone);
        Instant now = Instant.now();
        if (smsCodeRepository.countByPhoneAndCreatedAtAfter(phone, now.minusSeconds(60)) >= perMinute) {
            throw BizException.of(ErrorCode.SMS_TOO_FREQUENT, "发送过于频繁，请稍后再试");
        }
        if (smsCodeRepository.countByPhoneAndCreatedAtAfter(phone, now.minus(Duration.ofDays(1))) >= perDay) {
            throw BizException.of(ErrorCode.SMS_DAILY_LIMIT, "今日发送次数已达上限");
        }

        String code = randomCode();
        SmsCode record = new SmsCode();
        record.setPhone(phone);
        record.setCodeHash(sha256(code));
        record.setIpAddress(ip);
        record.setExpiresAt(now.plus(codeTtl));
        smsCodeRepository.save(record);

        smsSender.sendLoginCode(phone, code);
        return new AuthDtos.SendCodeResult(smsSender.developmentMode(), 60, codeTtl.toSeconds());
    }

    public AuthDtos.TokenPair login(String phone, String code, String ip, String userAgent, String deviceId) {
        codeVerifier.verifyAndConsume(phone, code);
        // 校验结束后才开启账户事务，避免每个并发登录占用两条数据库连接。
        return transactions.execute(status -> {
            User user = userRepository.findByPhone(phone).orElseGet(() -> registerNewUser(phone));
            user.setLastLoginAt(Instant.now());
            return issueTokens(user, ip, userAgent, deviceId);
        });
    }

    /** 刷新即轮换：老的立刻作废，防止 refresh token 被重复使用。 */
    @Transactional
    public AuthDtos.TokenPair refresh(String refreshToken, String ip, String userAgent, String deviceId) {
        AuthPrincipal principal = jwtService.parse(refreshToken, "refresh");
        if (principal == null) {
            throw BizException.of(ErrorCode.REFRESH_TOKEN_INVALID, "登录已失效，请重新登录");
        }
        RefreshToken stored = refreshTokenRepository
                .findByTokenHashAndStatus(sha256(refreshToken), "ACTIVE")
                .orElseThrow(() -> BizException.of(ErrorCode.REFRESH_TOKEN_INVALID, "登录已失效，请重新登录"));

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw BizException.of(ErrorCode.REFRESH_TOKEN_INVALID, "登录已过期，请重新登录");
        }
        stored.setStatus("ROTATED");

        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> BizException.of(ErrorCode.REFRESH_TOKEN_INVALID, "用户不存在"));
        return issueTokens(user, ip, userAgent, deviceId);
    }

    @Transactional
    public void logout(Long userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId);
        log.info("用户登出: userId={} 作废 {} 个 refresh token", userId, revoked);
    }

    private User registerNewUser(String phone) {
        Tenant tenant = new Tenant();
        tenant.setName("商家" + phone.substring(7));
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setTenantId(tenant.getId());
        user.setPhone(phone);
        user.setName("用户" + phone.substring(7));
        user = userRepository.save(user);

        log.info("新用户注册: userId={} tenantId={}", user.getId(), tenant.getId());
        return user;
    }

    private AuthDtos.TokenPair issueTokens(User user, String ip, String userAgent, String deviceId) {
        String access = jwtService.issueAccessToken(user.getId(), user.getTenantId(), user.getPhone());
        String refresh = jwtService.issueRefreshToken(user.getId(), user.getTenantId(), user.getPhone());

        RefreshToken row = new RefreshToken();
        row.setUserId(user.getId());
        row.setTokenHash(sha256(refresh));
        row.setDeviceId(deviceId);
        row.setIpAddress(ip);
        row.setUserAgent(userAgent == null ? null
                : userAgent.substring(0, Math.min(userAgent.length(), 400)));
        // 用 refresh 的 TTL。写死这一行是为了不重复老后端那个 bug。
        row.setExpiresAt(Instant.now().plus(jwtService.getRefreshTtl()));
        refreshTokenRepository.save(row);

        return new AuthDtos.TokenPair(
                access,
                refresh,
                jwtService.getAccessTtl().toSeconds(),
                new AuthDtos.UserInfo(user.getId(), user.getTenantId(), user.getPhone(), user.getName()));
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
