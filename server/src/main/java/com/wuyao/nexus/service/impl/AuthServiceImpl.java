package com.wuyao.nexus.service.impl;

import com.wuyao.nexus.dto.AuthResponse;
import com.wuyao.nexus.dto.LoginRequest;
import com.wuyao.nexus.dto.SendCodeRequest;
import com.wuyao.nexus.entity.RefreshToken;
import com.wuyao.nexus.entity.SmsVerificationRecord;
import com.wuyao.nexus.entity.Tenant;
import com.wuyao.nexus.entity.User;
import com.wuyao.nexus.exception.BusinessException;
import com.wuyao.nexus.repository.RefreshTokenRepository;
import com.wuyao.nexus.repository.SmsVerificationRecordRepository;
import com.wuyao.nexus.repository.TenantRepository;
import com.wuyao.nexus.repository.UserRepository;
import com.wuyao.nexus.service.AuthService;
import com.wuyao.nexus.service.SmsProvider;
import com.wuyao.nexus.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SmsVerificationRecordRepository smsRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SmsProvider smsProvider;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Value("${verification.code.length}")
    private Integer codeLength;

    @Value("${verification.code.expiration}")
    private Long codeExpiration;

    @Value("${rate-limit.sms.phone-per-minute}")
    private Integer phonePerMinute;

    @Value("${rate-limit.sms.phone-per-day}")
    private Integer phonePerDay;

    @Override
    @Transactional
    public void sendVerificationCode(SendCodeRequest request, HttpServletRequest httpRequest) {
        String phone = request.getPhone();
        String ipAddress = getClientIp(httpRequest);

        // 限流检查
        checkRateLimit(phone, ipAddress);

        // 生成验证码
        String code = generateCode();
        String codeHash = hashCode(code);

        // 保存验证码记录
        SmsVerificationRecord record = new SmsVerificationRecord();
        record.setPhone(phone);
        record.setCodeHash(codeHash);
        record.setPurpose(SmsVerificationRecord.Purpose.LOGIN);
        record.setIpAddress(ipAddress);
        record.setStatus(SmsVerificationRecord.Status.PENDING);
        record.setExpiresAt(LocalDateTime.now().plusSeconds(codeExpiration / 1000));
        smsRepository.save(record);

        // 缓存到Redis（用于快速验证）
        String redisKey = "sms:code:" + phone;
        redisTemplate.opsForValue().set(redisKey, codeHash, codeExpiration, TimeUnit.MILLISECONDS);

        // 发送短信
        smsProvider.sendVerificationCode(phone, code);

        log.info("验证码已发送: phone={}, ip={}", phone, ipAddress);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String phone = request.getPhone();
        String code = request.getCode();

        // 验证验证码
        verifyCode(phone, code);

        // 查找或创建用户（登录即注册）
        User user = userRepository.findByPhone(phone)
                .orElseGet(() -> createNewUser(phone));

        // 生成Token
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getPhone());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        // 保存Refresh Token
        saveRefreshToken(user.getId(), refreshToken, httpRequest);

        // 构造响应
        AuthResponse.UserInfo userInfo = new AuthResponse.UserInfo(
                user.getId(), user.getPhone(), user.getName(), user.getAvatarUrl());

        return new AuthResponse(accessToken, refreshToken, jwtUtil.getAccessTokenExpiration() / 1000, userInfo);
    }

    @Override
    @Transactional
    public AuthResponse refreshToken(String refreshToken, HttpServletRequest httpRequest) {
        String tokenHash = hashCode(refreshToken);

        // 验证Refresh Token
        RefreshToken token = refreshTokenRepository
                .findByTokenHashAndStatus(tokenHash, RefreshToken.Status.ACTIVE)
                .orElseThrow(() -> new BusinessException("Refresh Token无效或已过期"));

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            token.setStatus(RefreshToken.Status.EXPIRED);
            refreshTokenRepository.save(token);
            throw new BusinessException("Refresh Token已过期");
        }

        // 撤销旧Token
        token.setStatus(RefreshToken.Status.REVOKED);
        token.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(token);

        // 查找用户
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new BusinessException("用户不存在"));

        // 生成新Token
        String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getPhone());
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId());

        // 保存新Refresh Token
        saveRefreshToken(user.getId(), newRefreshToken, httpRequest);

        // 构造响应
        AuthResponse.UserInfo userInfo = new AuthResponse.UserInfo(
                user.getId(), user.getPhone(), user.getName(), user.getAvatarUrl());

        return new AuthResponse(newAccessToken, newRefreshToken, jwtUtil.getAccessTokenExpiration() / 1000, userInfo);
    }

    @Override
    @Transactional
    public void logout(Long userId, String deviceId) {
        if (deviceId != null) {
            refreshTokenRepository.deleteByUserIdAndDeviceId(userId, deviceId);
        }
    }

    private void checkRateLimit(String phone, String ipAddress) {
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);

        long phoneCount = smsRepository.countByPhoneAndCreatedAtAfter(phone, oneMinuteAgo);
        if (phoneCount >= phonePerMinute) {
            throw new BusinessException("发送过于频繁，请稍后再试");
        }

        long phoneDayCount = smsRepository.countByPhoneAndCreatedAtAfter(phone, oneDayAgo);
        if (phoneDayCount >= phonePerDay) {
            throw new BusinessException("今日发送次数已达上限");
        }
    }

    private void verifyCode(String phone, String code) {
        String redisKey = "sms:code:" + phone;
        String cachedHash = redisTemplate.opsForValue().get(redisKey);

        String codeHash = hashCode(code);

        if (cachedHash == null || !cachedHash.equals(codeHash)) {
            throw new BusinessException("验证码错误或已过期");
        }

        // 验证成功，删除缓存
        redisTemplate.delete(redisKey);
    }

    private User createNewUser(String phone) {
        // 创建默认租户
        Tenant tenant = new Tenant();
        tenant.setTenantCode("T" + System.currentTimeMillis());
        tenant.setName("个人租户");
        tenant.setStatus(Tenant.TenantStatus.ACTIVE);
        tenant = tenantRepository.save(tenant);

        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setName("用户" + phone.substring(7));
        user.setStatus(User.UserStatus.ACTIVE);
        user = userRepository.save(user);

        log.info("新用户注册成功: userId={}, phone={}, tenantId={}", user.getId(), phone, tenant.getId());

        return user;
    }

    private void saveRefreshToken(Long userId, String refreshToken, HttpServletRequest request) {
        String tokenHash = hashCode(refreshToken);
        String deviceId = request.getHeader("X-Device-Id");
        String ipAddress = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(tokenHash);
        token.setDeviceId(deviceId);
        token.setIpAddress(ipAddress);
        token.setUserAgent(userAgent);
        token.setStatus(RefreshToken.Status.ACTIVE);
        token.setExpiresAt(LocalDateTime.now().plusMillis(jwtUtil.getAccessTokenExpiration()));

        refreshTokenRepository.save(token);
    }

    private String generateCode() {
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < codeLength; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    private String hashCode(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash算法不可用", e);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
