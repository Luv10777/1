package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.repository.SmsCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class LoginCodeVerifier {
    private static final int MAX_ATTEMPTS = 5;
    private final SmsCodeRepository repository;

    /** 失败计数与验证码消费独立提交，后面的登录事务失败也不能撤销它们。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BizException.class)
    public void verifyAndConsume(String phone, String code) {
        var record = repository.findFirstByPhoneAndPurposeOrderByIdDesc(phone, "LOGIN")
                .orElseThrow(() -> BizException.of(ErrorCode.CODE_INVALID, "请先获取验证码"));
        if (record.getConsumedAt() != null) {
            throw BizException.of(ErrorCode.CODE_INVALID, "验证码已使用，请重新获取");
        }
        if (!record.getExpiresAt().isAfter(Instant.now())) {
            throw BizException.of(ErrorCode.CODE_EXPIRED, "验证码已过期");
        }
        if (record.getAttempts() >= MAX_ATTEMPTS) {
            throw BizException.of(ErrorCode.CODE_INVALID, "尝试次数过多，请重新获取");
        }
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(record.getCodeHash()))) {
                record.setAttempts(record.getAttempts() + 1);
                throw BizException.of(ErrorCode.CODE_INVALID, "验证码不正确");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        record.setConsumedAt(Instant.now());
    }
}
