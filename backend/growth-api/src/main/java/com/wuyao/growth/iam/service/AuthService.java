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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;

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
    private static final DefaultRedisScript<Long> IP_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local minute = redis.call('INCR', KEYS[1])
            if minute == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
            if minute > tonumber(ARGV[1]) then return 0 end
            local day = redis.call('INCR', KEYS[2])
            if day == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[4]) end
            if day > tonumber(ARGV[2]) then return 1 end
            local globalMinute = redis.call('INCR', KEYS[3])
            if globalMinute == 1 then redis.call('PEXPIRE', KEYS[3], ARGV[3]) end
            if globalMinute > tonumber(ARGV[5]) then return 0 end
            local globalDay = redis.call('INCR', KEYS[4])
            if globalDay == 1 then redis.call('PEXPIRE', KEYS[4], ARGV[4]) end
            if globalDay > tonumber(ARGV[6]) then return 1 end
            return 2
            """, Long.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SmsCodeRepository smsCodeRepository;
    private final SmsSender smsSender;
    private final JwtService jwtService;
    private final LoginCodeVerifier codeVerifier;
    private final PasswordLoginVerifier passwordVerifier;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;

    @Value("${growth.sms.code-length:6}")
    private int codeLength;

    @Value("${growth.sms.code-ttl:5m}")
    private Duration codeTtl;

    @Value("${growth.sms.per-phone-per-minute:1}")
    private int perMinute;

    @Value("${growth.sms.per-phone-per-day:10}")
    private int perDay;

    @Value("${growth.sms.per-ip-per-minute:5}")
    private int perIpMinute;

    @Value("${growth.sms.per-ip-per-day:100}")
    private int perIpDay;

    @Value("${growth.sms.global-per-minute:100}")
    private int globalMinute;

    @Value("${growth.sms.global-per-day:10000}")
    private int globalDay;

    @Transactional
    public AuthDtos.SendCodeResult sendCode(String phone, String ip) {
        enforceIpLimit(ip);
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

    private void enforceIpLimit(String ip) {
        String identity = (ip == null || ip.isBlank()) ? "unknown" : ip.trim();
        try {
            Long result = redis.execute(IP_LIMIT_SCRIPT,
                    List.of("auth:sms:{send}:ip:" + identity + ":minute",
                            "auth:sms:{send}:ip:" + identity + ":day",
                            "auth:sms:{send}:global:minute", "auth:sms:{send}:global:day"),
                    Integer.toString(Math.max(1, perIpMinute)), Integer.toString(Math.max(1, perIpDay)),
                    "60000", "86400000", Integer.toString(Math.max(1, globalMinute)),
                    Integer.toString(Math.max(1, globalDay)));
            if (Long.valueOf(0L).equals(result)) {
                throw BizException.of(ErrorCode.SMS_TOO_FREQUENT, "发送过于频繁，请稍后再试");
            }
            if (Long.valueOf(1L).equals(result)) {
                throw BizException.of(ErrorCode.SMS_DAILY_LIMIT, "今日发送次数已达上限");
            }
            if (!Long.valueOf(2L).equals(result)) throw new IllegalStateException("短信限流未返回有效结果");
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // Do not allow an infrastructure failure to disable the anti-abuse control.
            log.error("短信 IP 限流不可用，拒绝发送", e);
            throw BizException.of(ErrorCode.SMS_TOO_FREQUENT, "短信服务暂时不可用，请稍后再试");
        }
    }

    public AuthDtos.TokenPair login(String phone, String code, String ip, String userAgent, String deviceId) {
        codeVerifier.verifyAndConsume(phone, code);
        // 校验结束后才开启账户事务，避免每个并发登录占用两条数据库连接。
        return transactions.execute(status -> {
            smsCodeRepository.lockPhone(phone);
            User user = userRepository.findByPhone(phone).orElseGet(() -> registerNewUser(phone));
            if (!"ACTIVE".equals(user.getStatus())) {
                throw BizException.of(ErrorCode.UNAUTHORIZED, "账号已停用，请联系商户管理员");
            }
            user.setLastLoginAt(Instant.now());
            return issueTokens(user, ip, userAgent, deviceId);
        });
    }

    public AuthDtos.TokenPair loginWithPassword(String account, String password, String ip,
                                                String userAgent, String deviceId) {
        Long userId = passwordVerifier.verify(account, password);
        return transactions.execute(status -> {
            User user = userRepository.findForUpdate(userId)
                    .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                    .orElseThrow(() -> BizException.of(ErrorCode.PASSWORD_INVALID, "账号不可用"));
            user.setLastLoginAt(Instant.now());
            return issueTokens(user, ip, userAgent, deviceId);
        });
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserInfo currentUser(Long userId) {
        User user = userRepository.findById(userId)
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED, "用户不存在"));
        return new AuthDtos.UserInfo(user.getId(), user.getTenantId(), user.getPhone(), user.getName(), user.getRole());
    }

    /** 刷新即轮换：老的立刻作废，防止 refresh token 被重复使用。 */
    @Transactional
    public AuthDtos.TokenPair refresh(String refreshToken, String ip, String userAgent, String deviceId) {
        AuthPrincipal principal = jwtService.parse(refreshToken, "refresh");
        if (principal == null) {
            throw BizException.of(ErrorCode.REFRESH_TOKEN_INVALID, "登录已失效，请重新登录");
        }
        // Lock the user before the refresh row: logout uses the same lock order.
        User user = userRepository.findForUpdate(principal.userId())
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .filter(candidate -> principal.tenantId().equals(candidate.getTenantId()))
                .filter(candidate -> principal.tokenVersion() == candidate.getTokenVersion())
                .orElseThrow(() -> BizException.of(ErrorCode.REFRESH_TOKEN_INVALID, "账号不可用或登录已失效"));
        RefreshToken stored = refreshTokenRepository
                .findByTokenHashAndStatus(sha256(refreshToken), "ACTIVE")
                .orElseThrow(() -> BizException.of(ErrorCode.REFRESH_TOKEN_INVALID, "登录已失效，请重新登录"));

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw BizException.of(ErrorCode.REFRESH_TOKEN_INVALID, "登录已过期，请重新登录");
        }
        stored.setStatus("ROTATED");

        return issueTokens(user, ip, userAgent, deviceId);
    }

    @Transactional
    public void logout(Long userId) {
        User user = userRepository.findForUpdate(userId)
                .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED, "账号不可用"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        int revoked = refreshTokenRepository.revokeAllForUser(userId);
        log.info("用户登出: userId={} 作废 {} 个 refresh token", userId, revoked);
    }

    User registerNewUser(String phone) {
        Tenant tenant = new Tenant();
        tenant.setName("商家" + phone.substring(7));
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setTenantId(tenant.getId());
        user.setPhone(phone);
        user.setName("用户" + phone.substring(7));
        // 自己注册的人就是这个新商户的管理员。管理员添加的店员走 AccountService.createStaff，不会到这里。
        user.setRole(AccountService.OWNER);
        user = userRepository.save(user);

        log.info("新用户注册: userId={} tenantId={}", user.getId(), tenant.getId());
        return user;
    }

    /** 微信登录绑定后复用现有 JWT / refresh token 发行逻辑。 */
    AuthDtos.TokenPair issueTokensForUser(Long userId, String ip, String userAgent, String deviceId) {
        return transactions.execute(status -> {
            User user = userRepository.findForUpdate(userId)
                    .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                    .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED, "账号不可用"));
            user.setLastLoginAt(Instant.now());
            return issueTokens(user, ip, userAgent, deviceId);
        });
    }

    private AuthDtos.TokenPair issueTokens(User user, String ip, String userAgent, String deviceId) {
        // Callers hold the user row lock, serializing issuance with logout.
        String access = jwtService.issueAccessToken(user.getId(), user.getTenantId(), user.getPhone(), user.getTokenVersion());
        String refresh = jwtService.issueRefreshToken(user.getId(), user.getTenantId(), user.getPhone(), user.getTokenVersion());

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
                new AuthDtos.UserInfo(user.getId(), user.getTenantId(), user.getPhone(), user.getName(), user.getRole()));
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
