package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.RefreshTokenRepository;
import com.wuyao.growth.iam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 商户内的账号与角色。其他模块判断"这个人是不是老板"、员工管理增删店员，都走这里。
 *
 * users 是系统级表，没有行级安全：这里的每个查询都必须自己核对 tenant_id，
 * 否则就会读到或改到别的商户的账号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    public static final String OWNER = "OWNER";
    public static final String STAFF = "STAFF";
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final String REMOVED = "REMOVED";
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;

    /** 当前商户里这个可用账号的角色。停用、移除或不属于当前商户的账号一律拒绝。 */
    @Transactional(readOnly = true)
    public String roleOf(Long userId) {
        return active(userId).getRole();
    }

    @Transactional(readOnly = true)
    public boolean isOwner(Long userId) {
        return OWNER.equals(roleOf(userId));
    }

    @Transactional(readOnly = true)
    public void requireOwner(Long userId) {
        if (!isOwner(userId)) throw BizException.of(ErrorCode.FORBIDDEN, "只有老板可以执行这个操作");
    }

    /** 当前商户未移除的账号，老板在前。 */
    @Transactional(readOnly = true)
    public List<AccountDtos.Account> members() {
        return users.findAllByTenantIdAndStatusNotOrderByIdAsc(TenantContext.require(), REMOVED).stream()
                .sorted((a, b) -> Boolean.compare(OWNER.equals(b.getRole()), OWNER.equals(a.getRole())))
                .map(AccountDtos.Account::of).toList();
    }

    /**
     * 预先登记一个店员。对方用这个手机号验证码登录时会直接进入本商户，而不是新开一个商户。
     * 一个手机号目前只能属于一个商户：已经注册过的手机号加不进来。
     */
    @Transactional
    public AccountDtos.Account createStaff(String phone, String name) {
        Long tenantId = TenantContext.require();
        String number = phone == null ? "" : phone.trim();
        if (!PHONE.matcher(number).matches()) throw BizException.of(ErrorCode.PHONE_INVALID, "手机号格式不正确");
        users.findByPhone(number).ifPresent(existing -> {
            if (!tenantId.equals(existing.getTenantId())) {
                throw BizException.of(ErrorCode.CONFLICT, "这个手机号已经注册了其他商户，暂时无法添加");
            }
            throw BizException.of(ErrorCode.CONFLICT, DISABLED.equals(existing.getStatus())
                    ? "这个手机号对应的成员已停用，可以在列表中重新启用" : "这个手机号已经是本商户的成员");
        });
        User user = new User();
        user.setTenantId(tenantId);
        user.setPhone(number);
        user.setName(name.trim());
        user.setRole(STAFF);
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 对方恰好在这一刻用同一个手机号自己注册了。
            throw BizException.of(ErrorCode.CONFLICT, "这个手机号刚刚被注册，请刷新后重试");
        }
        log.info("添加店员: tenantId={} userId={}", tenantId, user.getId());
        return AccountDtos.Account.of(user);
    }

    @Transactional
    public AccountDtos.Account renameStaff(Long userId, String name) {
        User user = staffForUpdate(userId);
        user.setName(name.trim());
        user.setUpdatedAt(Instant.now());
        return AccountDtos.Account.of(user);
    }

    /** 停用立即生效：令牌版本加一，已签发的访问令牌和刷新令牌都随之作废。 */
    @Transactional
    public AccountDtos.Account setStaffEnabled(Long userId, boolean enabled) {
        User user = staffForUpdate(userId);
        if (enabled == ACTIVE.equals(user.getStatus())) return AccountDtos.Account.of(user);
        user.setStatus(enabled ? ACTIVE : DISABLED);
        user.setUpdatedAt(Instant.now());
        if (!enabled) signOutEverywhere(user);
        log.info("店员{}: tenantId={} userId={}", enabled ? "启用" : "停用", user.getTenantId(), userId);
        return AccountDtos.Account.of(user);
    }

    /**
     * 移除店员。账号行保留（业务记录里的创建人仍指向它），但释放手机号，
     * 这样对方以后可以用同一个手机号注册自己的商户，或被别的商户添加。
     */
    @Transactional
    public void removeStaff(Long userId) {
        User user = staffForUpdate(userId);
        user.setStatus(REMOVED);
        user.setPhone(null);
        user.setUpdatedAt(Instant.now());
        signOutEverywhere(user);
        log.info("移除店员: tenantId={} userId={}", user.getTenantId(), userId);
    }

    private void signOutEverywhere(User user) {
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokens.revokeAllForUser(user.getId());
    }

    private User active(Long userId) {
        Long tenantId = TenantContext.require();
        return users.findById(userId)
                .filter(user -> tenantId.equals(user.getTenantId()) && ACTIVE.equals(user.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "账号不可用或不属于当前商户"));
    }

    /** 员工管理只能改店员；老板的账号不通过这些接口变更。 */
    private User staffForUpdate(Long userId) {
        Long tenantId = TenantContext.require();
        User user = users.findForUpdate(userId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()) && !REMOVED.equals(candidate.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "成员不存在"));
        if (!STAFF.equals(user.getRole())) throw BizException.of(ErrorCode.BAD_REQUEST, "老板的账号不能在这里修改");
        return user;
    }
}
