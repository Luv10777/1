package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class PasswordLoginVerifier {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    private static final String DUMMY_HASH = ENCODER.encode(UUID.randomUUID().toString());
    private final UserRepository users;

    /** 失败次数单独提交；同一账号的并发尝试通过行锁串行处理。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BizException.class)
    public Long verify(String account, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalid();
        var user = users.findByUsername(account).orElse(null);
        Instant now = Instant.now();
        boolean matches = ENCODER.matches(password,
                user == null || user.getPasswordHash() == null ? DUMMY_HASH : user.getPasswordHash());
        if (user == null || user.getPasswordHash() == null || !"ACTIVE".equals(user.getStatus())) {
            throw invalid();
        }
        if (user.getPasswordLockedUntil() != null) {
            if (user.getPasswordLockedUntil().isAfter(now)) throw invalid();
            user.setPasswordLockedUntil(null);
            user.setPasswordFailedAttempts(0);
        }
        if (!matches) {
            user.setPasswordFailedAttempts(user.getPasswordFailedAttempts() + 1);
            if (user.getPasswordFailedAttempts() >= 5) {
                user.setPasswordLockedUntil(now.plus(Duration.ofMinutes(15)));
            }
            throw invalid();
        }
        user.setPasswordFailedAttempts(0);
        return user.getId();
    }

    private BizException invalid() {
        return BizException.of(ErrorCode.PASSWORD_INVALID, "账号或密码错误，连续失败五次后请等待 15 分钟再试");
    }
}
