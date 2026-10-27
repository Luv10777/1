package com.wuyao.growth.community;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.security.AuthPrincipal;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 平台用户与账号管理：跨商户查看用户，平台停用与解除停用。
 * 先核对运营身份再读数据；停用和审计记录在用户所属商户的租户上下文里同一事务提交。
 */
@Service
@RequiredArgsConstructor
public class PlatformUserService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED", "SUSPENDED", "REMOVED");
    private static final Set<String> ROLES = Set.of("OWNER", "STAFF");

    private final PlatformManagementService management;
    private final PlatformProperties config;
    private final PlatformAccountDirectory directory;
    private final PlatformManagementAuditRepository audits;
    private final BillingService billing;
    private final PlatformTransactionManager transactionManager;

    /** 默认不列出已被商户移除的账号；按状态筛选 REMOVED 时才显示。 */
    public PageResult<PlatformDtos.UserView> users(AuthPrincipal me, int page, int size, String status, String role, String keyword) {
        management.requireOperator(me);
        if (page < 0 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST, "分页参数无效");
        checkFilter(status, STATUSES);
        checkFilter(role, ROLES);
        Map<Long, String> tenantNames = tenantNames();
        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        var matched = directory.users().stream()
                .filter(u -> blank(status) ? !"REMOVED".equals(u.status()) : status.equals(u.status()))
                .filter(u -> blank(role) || role.equals(u.role()))
                .filter(u -> key.isEmpty() || Stream.of(u.name(), u.phone(), u.username(), tenantNames.get(u.tenantId()), String.valueOf(u.id()))
                        .filter(Objects::nonNull).anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(key)))
                .toList();
        int start = (int) Math.min((long) page * size, matched.size());
        int end = Math.min(start + size, matched.size());
        // 会员信息只为当前页涉及的商户读取，每个商户读一次。
        Map<Long, BillingDtos.Summary> memberships = new HashMap<>();
        var items = matched.subList(start, end).stream()
                .map(u -> view(u, tenantNames.get(u.tenantId()), memberships.computeIfAbsent(u.tenantId(), this::membership)))
                .toList();
        return new PageResult<>(items, page, size, matched.size(), (int) Math.ceil((double) matched.size() / size));
    }

    public PlatformDtos.UserSummary summary(AuthPrincipal me) {
        management.requireOperator(me);
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        var current = directory.users().stream().filter(u -> !"REMOVED".equals(u.status())).toList();
        return new PlatformDtos.UserSummary(current.size(),
                current.stream().filter(u -> u.lastLoginAt() != null && u.lastLoginAt().isAfter(weekAgo)).count(),
                current.stream().filter(u -> u.createdAt().isAfter(weekAgo)).count(),
                current.stream().filter(u -> PlatformAccountDirectory.SUSPENDED.equals(u.status())).count());
    }

    /** 停用立即让对方所有设备下线；不能停用自己和平台运营账号。 */
    public PlatformDtos.UserView suspension(AuthPrincipal me, Long userId, PlatformDtos.UserSuspensionRequest request) {
        management.requireOperator(me);
        String note = request.note() == null ? "" : request.note().trim();
        if (note.length() < 5 || note.length() > 500) throw BizException.of(ErrorCode.BAD_REQUEST, "请填写5至500字的处理依据");
        boolean suspend = request.suspended();
        if (suspend && userId.equals(me.userId())) throw BizException.of(ErrorCode.CONFLICT, "不能停用自己的账号");
        if (suspend && config.getOperatorUserIds().contains(userId)) {
            throw BizException.of(ErrorCode.CONFLICT, "平台运营账号不能在这里停用，请先从运营名单中移除");
        }
        Long tenantId = directory.user(userId).tenantId();
        var updated = inScope(tenantId, false, () -> {
            var result = suspend ? directory.suspend(userId) : directory.restore(userId);
            var event = new PlatformManagementAudit();
            event.setTenantId(tenantId); event.setActorUserId(me.userId()); event.setTargetType("USER");
            event.setTargetId(userId); event.setAction(suspend ? "SUSPEND_USER" : "RESTORE_USER"); event.setNote(note);
            audits.saveAndFlush(event);
            return result;
        });
        return view(updated, tenantNames().get(tenantId), membership(tenantId));
    }

    /** 会员按商户计：给某个账号开通或关闭，就是给他所属的商户开通或关闭。 */
    public PlatformDtos.UserView membership(AuthPrincipal me, Long userId, PlatformDtos.MembershipRequest request) {
        management.requireOperator(me);
        String note = reason(request.note());
        boolean grant = request.granted();
        if (grant && request.planId() == null) throw BizException.of(ErrorCode.BAD_REQUEST, "请选择要开通的套餐");
        return billingChange(me, userId, grant ? "GRANT_MEMBERSHIP" : "REVOKE_MEMBERSHIP", () -> {
            if (!grant) { billing.revokeMembership(me.userId()); return note; }
            var plan = billing.grantMembership(request.planId(), request.requestKey(), me.userId());
            return plan == null ? null : "「" + plan.name() + "」" + plan.durationDays() + " 天：" + note;
        });
    }

    public PlatformDtos.UserView credits(AuthPrincipal me, Long userId, PlatformDtos.CreditGrantRequest request) {
        management.requireOperator(me);
        String note = reason(request.note());
        return billingChange(me, userId, "GRANT_CREDITS", () ->
                billing.grantCredits(request.credits(), request.requestKey(), me.userId()) ? request.credits() + " 烟火：" + note : null);
    }

    /**
     * 账户变动和审计记录在用户所属商户的租户里同一事务提交。
     * change 返回审计说明；返回 null 表示同一请求已处理过，不再重复记录。
     */
    private PlatformDtos.UserView billingChange(AuthPrincipal me, Long userId, String action, Supplier<String> change) {
        Long tenantId = directory.user(userId).tenantId();
        inScope(tenantId, false, () -> {
            String note = change.get();
            if (note == null) return null;
            var event = new PlatformManagementAudit();
            event.setTenantId(tenantId); event.setActorUserId(me.userId()); event.setTargetType("USER");
            event.setTargetId(userId); event.setAction(action); event.setNote(note);
            audits.saveAndFlush(event);
            return null;
        });
        return view(directory.user(userId), tenantNames().get(tenantId), membership(tenantId));
    }

    private static String reason(String note) {
        String value = note == null ? "" : note.trim();
        if (value.length() < 5 || value.length() > 300) throw BizException.of(ErrorCode.BAD_REQUEST, "请填写5至300字的处理依据");
        return value;
    }

    private PlatformDtos.UserView view(PlatformAccountDirectory.PlatformUser u, String tenantName, BillingDtos.Summary member) {
        return new PlatformDtos.UserView(u.id(), u.tenantId(), tenantName, u.name(), PlatformManagementService.maskPhone(u.phone()),
                u.username(), u.role(), u.status(), u.lastLoginAt(), u.createdAt(), u.suspendedAt(),
                member.memberActive(), member.memberUntil(), member.plan() == null ? null : member.plan().name(),
                member.available(), config.getOperatorUserIds().contains(u.id()));
    }

    private BillingDtos.Summary membership(Long tenantId) {
        return inScope(tenantId, true, () -> billing.summary(false, false));
    }

    private Map<Long, String> tenantNames() {
        return directory.scopes().stream().collect(Collectors.toMap(PlatformAccountDirectory.Scope::id,
                s -> s.name() == null ? "" : s.name()));
    }

    private <T> T inScope(Long tenantId, boolean readOnly, Supplier<T> work) {
        return TenantContext.runAs(tenantId, () -> {
            var tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setReadOnly(readOnly);
            return tx.execute(status -> work.get());
        });
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void checkFilter(String value, Set<String> allowed) {
        if (!blank(value) && !allowed.contains(value)) throw BizException.of(ErrorCode.BAD_REQUEST, "筛选条件无效");
    }
}
