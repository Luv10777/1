package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.entity.User;
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
        var user = users.findByUsername(account).or(() -> users.findByPhone(account)).orElse(null);
        boolean matches = ENCODER.matches(password,
                user == null || user.getPasswordHash() == null ? DUMMY_HASH : user.getPasswordHash());
        if (user == null || user.getPasswordHash() == null || !"ACTIVE".equals(user.getStatus())) {
            throw invalid();
        }
        if (!countAttempt(user, matches)) throw invalid();
        return user.getId();
    }

    /**
     * 已登录的人改密码前核对当前密码。和登录共用失败计数：拿到别人登录态的人也不能借这里无限猜密码。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BizException.class)
    public void verifyCurrent(Long userId, String password) {
        var user = users.findForUpdate(userId)
                .filter(candidate -> candidate.getPasswordHash() != null && "ACTIVE".equals(candidate.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST, "这个账号还没有设置密码"));
        boolean matches = password.getBytes(StandardCharsets.UTF_8).length <= 72
                && ENCODER.matches(password, user.getPasswordHash());
        if (!countAttempt(user, matches)) {
            throw BizException.of(ErrorCode.PASSWORD_INVALID, "当前密码不正确，连续失败五次后请等待 15 分钟，或改用短信验证码");
        }
    }

    /** @return 这次能否放行。锁定期内一律不放行，密码对了也一样。 */
    private boolean countAttempt(User user, boolean matches) {
        Instant now = Instant.now();
        if (user.getPasswordLockedUntil() != null) {
            if (user.getPasswordLockedUntil().isAfter(now)) return false;
            user.setPasswordLockedUntil(null);
            user.setPasswordFailedAttempts(0);
        }
        if (!matches) {
            user.setPasswordFailedAttempts(user.getPasswordFailedAttempts() + 1);
            if (user.getPasswordFailedAttempts() >= 5) {
                user.setPasswordLockedUntil(now.plus(Duration.ofMinutes(15)));
            }
            return false;
        }
        user.setPasswordFailedAttempts(0);
        return true;
    }

    private BizException invalid() {
        return BizException.of(ErrorCode.PASSWORD_INVALID, "账号或密码错误，连续失败五次后请等待 15 分钟再试");
    }

    /** bcrypt 只看前 72 字节，再长的部分不参与校验，所以按字节拦。 */
    static boolean acceptable(String password) {
        return password != null && password.length() >= 8 && password.length() <= 64
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    static String hashPassword(String password) {
        return ENCODER.encode(password);
    }
}
