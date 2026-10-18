package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.entity.Tenant;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.RefreshTokenRepository;
import com.wuyao.growth.iam.repository.TenantRepository;
import com.wuyao.growth.iam.repository.UserRepository;
import com.wuyao.growth.iam.repository.WechatAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 本人注销账号，立即生效、不能恢复。
 *
 * 店员，或者商户里还有别的管理员时，只注销自己；商户里最后一个管理员注销时，整个商户一起关闭：
 * 所有成员一起注销，门店关闭，对外的店铺、商品和信息发布下线，余额和会员作废。
 *
 * 注销的账号行保留（业务记录里的创建人仍指向它），但手机号、登录账号、密码、微信绑定和名字都清掉，
 * 手机号和微信以后可以重新注册。各模块自己的收尾由 {@link AccountClosureGuard} 完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountClosureService {

    private static final String ACTIVE = "ACTIVE";
    private static final String REMOVED = "REMOVED";
    private static final String CLOSED = "CLOSED";
    private static final String CLOSED_NAME = "已注销用户";

    private final UserRepository users;
    private final TenantRepository tenants;
    private final RefreshTokenRepository refreshTokens;
    private final WechatAccountRepository wechatAccounts;
    private final LoginCodeVerifier codeVerifier;
    private final PasswordLoginVerifier passwordVerifier;
    private final List<AccountClosureGuard> guards;
    private final TransactionTemplate transactions;

    /** 注销前告诉本人会发生什么：只注销自己还是关闭商户、会失去什么、现在能不能注销。 */
    public AccountDtos.ClosurePreview preview(Long userId) {
        return transactions.execute(status -> {
            User user = active(userId, false);
            AccountClosureGuard.Closure closure = scope(user);
            return new AccountDtos.ClosurePreview(closure.wholeTenant(), closure.userIds().size() - 1,
                    user.getPhone() != null,
                    guards.stream().flatMap(guard -> guard.losses(closure).stream()).toList(),
                    blocker(closure).orElse(null));
        });
    }

    /**
     * 绑了手机号的账号核对发到本人手机的验证码，没绑的核对当前密码：只拿到登录态的人不能注销别人的账号。
     * 成功后所有设备上的登录立即失效。
     */
    public void close(Long userId, String code, String currentPassword) {
        // 先看现在能不能注销，免得验证码白白用掉。
        User account = transactions.execute(status -> {
            User user = active(userId, false);
            blocker(scope(user)).ifPresent(reason -> { throw BizException.of(ErrorCode.CONFLICT, reason); });
            return user;
        });
        if (account.getPhone() != null) {
            if (code == null || code.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST, "请输入短信验证码");
            codeVerifier.verifyAndConsume(account.getPhone(), code);
        } else {
            if (currentPassword == null || currentPassword.isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "请输入当前密码");
            }
            passwordVerifier.verifyCurrent(userId, currentPassword);
        }

        transactions.executeWithoutResult(status -> {
            // 先锁商户再锁人，和往商户里加店员的顺序一致。
            Tenant tenant = tenants.findForUpdate(account.getTenantId())
                    .filter(candidate -> ACTIVE.equals(candidate.getStatus()))
                    .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "商户已关闭"));
            User user = active(userId, true);
            AccountClosureGuard.Closure closure = scope(user);
            blocker(closure).ifPresent(reason -> { throw BizException.of(ErrorCode.CONFLICT, reason); });
            guards.forEach(guard -> guard.closed(closure));
            for (Long id : closure.userIds()) {
                erase(id.equals(userId) ? user : users.findForUpdate(id).orElseThrow());
            }
            if (closure.wholeTenant()) {
                tenant.setStatus(CLOSED);
                tenant.setUpdatedAt(Instant.now());
            }
            log.info("账号注销: tenantId={} userId={} 关闭商户={} 一并注销 {} 个成员",
                    tenant.getId(), userId, closure.wholeTenant(), closure.userIds().size() - 1);
        });
    }

    /** 店员或者还有别的管理员时只注销自己；否则连同商户里全部未移除的成员一起。 */
    private AccountClosureGuard.Closure scope(User user) {
        if (!AccountService.OWNER.equals(user.getRole())) {
            return new AccountClosureGuard.Closure(user.getTenantId(), List.of(user.getId()), false);
        }
        List<User> members = users.findAllByTenantIdAndStatusNotOrderByIdAsc(user.getTenantId(), REMOVED);
        boolean anotherOwner = members.stream().anyMatch(member -> !member.getId().equals(user.getId())
                && AccountService.OWNER.equals(member.getRole()) && ACTIVE.equals(member.getStatus()));
        if (anotherOwner) return new AccountClosureGuard.Closure(user.getTenantId(), List.of(user.getId()), false);
        return new AccountClosureGuard.Closure(user.getTenantId(), members.stream().map(User::getId).toList(), true);
    }

    private Optional<String> blocker(AccountClosureGuard.Closure closure) {
        return guards.stream().flatMap(guard -> guard.blocksClosing(closure).stream()).findFirst();
    }

    private void erase(User user) {
        user.setStatus(REMOVED);
        user.setPhone(null);
        user.setUsername(null);
        user.setPasswordHash(null);
        user.setPasswordFailedAttempts(0);
        user.setPasswordLockedUntil(null);
        user.setName(CLOSED_NAME);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(Instant.now());
        refreshTokens.revokeAllForUser(user.getId());
        wechatAccounts.deleteByUser(user.getId());
    }

    private User active(Long userId, boolean lock) {
        Long tenantId = TenantContext.require();
        return (lock ? users.findForUpdate(userId) : users.findById(userId))
                .filter(user -> tenantId.equals(user.getTenantId()) && ACTIVE.equals(user.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "账号不可用或不属于当前商户"));
    }
}
