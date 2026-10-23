package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.RefreshTokenRepository;
import com.wuyao.growth.iam.repository.TenantRepository;
import com.wuyao.growth.iam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Directory DTOs for explicitly authorized platform services; never exposes credentials. */
@Service
@RequiredArgsConstructor
public class PlatformAccountDirectory {
    public static final String SUSPENDED = "SUSPENDED";
    private final TenantRepository tenants;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;

    public record Scope(Long id, String name) {}

    @Transactional(readOnly = true)
    public List<Scope> scopes() {
        return tenants.findAll(Sort.by("id")).stream().map(t -> new Scope(t.getId(), t.getName())).toList();
    }

    @Transactional(readOnly = true)
    public AccountDtos.Account account(Long userId) {
        return users.findById(userId).filter(u -> TenantContext.require().equals(u.getTenantId()))
                .map(u -> AccountDtos.Account.of(u, null)).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    /** 平台账号管理看到的用户资料；不含密码、令牌等凭证。 */
    public record PlatformUser(Long id, Long tenantId, String name, String phone, String username, String role,
                               String status, Instant lastLoginAt, Instant createdAt, Instant suspendedAt) {
        static PlatformUser of(User u) {
            return new PlatformUser(u.getId(), u.getTenantId(), u.getName(), u.getPhone(), u.getUsername(), u.getRole(),
                    u.getStatus(), u.getLastLoginAt(), u.getCreatedAt(), u.getSuspendedAt());
        }
    }

    /** 全平台用户，新注册的在前。users 是系统级表，不受租户 RLS 约束，调用方必须先核对平台运营身份。 */
    @Transactional(readOnly = true)
    public List<PlatformUser> users() {
        return users.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id")).stream().map(PlatformUser::of).toList();
    }

    @Transactional(readOnly = true)
    public PlatformUser user(Long userId) {
        return users.findById(userId).map(PlatformUser::of).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    /**
     * 平台停用：记下停用前的状态，令牌版本加一并作废全部刷新令牌，所有设备立即下线。
     * 商户不能撤销平台停用，见 AccountService。
     */
    @Transactional
    public PlatformUser suspend(Long userId) {
        User user = users.findForUpdate(userId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "用户不存在"));
        if (SUSPENDED.equals(user.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "账号已处于平台停用状态，请刷新后核对");
        if ("REMOVED".equals(user.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "账号已被商户移除，无需停用");
        user.setStatusBeforeSuspension(user.getStatus());
        user.setStatus(SUSPENDED);
        user.setSuspendedAt(Instant.now());
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(Instant.now());
        refreshTokens.revokeAllForUser(userId);
        return PlatformUser.of(user);
    }

    /** 解除平台停用，回到停用前的状态（商户停用的店员解除后仍是停用）。 */
    @Transactional
    public PlatformUser restore(Long userId) {
        User user = users.findForUpdate(userId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "用户不存在"));
        if (!SUSPENDED.equals(user.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "账号不在平台停用状态，请刷新后核对");
        user.setStatus(user.getStatusBeforeSuspension() == null ? "ACTIVE" : user.getStatusBeforeSuspension());
        user.setStatusBeforeSuspension(null);
        user.setSuspendedAt(null);
        user.setUpdatedAt(Instant.now());
        return PlatformUser.of(user);
    }

    /** Serialize identity submissions/reviews for the same user, including the initial insert. */
    @Transactional
    public void lockAccount(Long userId) {
        users.findForUpdate(userId).filter(u -> TenantContext.require().equals(u.getTenantId()))
                .filter(u -> "ACTIVE".equals(u.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "用户不存在或已停用"));
    }
}
