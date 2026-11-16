package com.wuyao.growth.backoffice;

import com.wuyao.growth.billing.BillingProperties;
import com.wuyao.growth.community.PlatformProperties;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Persisted administrator roles supplement, rather than replace, configured bootstrap operators. */
@Service @RequiredArgsConstructor
public class BackofficeAccess {
    public static final Set<String> ALL = Set.of("REVIEW", "USERS", "FINANCE", "ALLIANCE", "REFERRAL", "PARTNERS", "SECURITY", "USER_BILLING");
    private static final Map<String, Set<String>> ROLES = Map.of(
            "ADMIN", ALL, "REVIEWER", Set.of("REVIEW"), "FINANCE", Set.of("FINANCE", "ALLIANCE", "REFERRAL", "PARTNERS"),
            "SUPPORT", Set.of("USERS"));
    private final EntityManager em;
    private final PlatformProperties platform;
    private final BillingProperties billing;
    private final PlatformAccountDirectory directory;

    public record Access(boolean operator, Set<String> permissions, List<String> roles) {}
    public record Assignment(Long uid, String name, List<String> roles, boolean platformBootstrap, boolean billingBootstrap) {}
    public record RoleRequest(@NotNull @Min(10000000) @Max(99999999) Long uid,
                              @NotNull @Size(max=4) List<@NotNull @Pattern(regexp="ADMIN|REVIEWER|FINANCE|SUPPORT") String> roles) {}

    @Transactional(readOnly=true)
    public Access access(Long userId) {
        if (userId == null) return new Access(false, Set.of(), List.of());
        @SuppressWarnings("unchecked") List<String> roles = em.createNativeQuery("SELECT role FROM platform_roles WHERE user_id=:id ORDER BY role")
                .setParameter("id", userId).getResultList();
        Set<String> permissions = new HashSet<>();
        roles.forEach(r -> permissions.addAll(ROLES.getOrDefault(r, Set.of())));
        if (platform.getOperatorUserIds().contains(userId)) permissions.addAll(Set.of("REVIEW", "USERS", "ALLIANCE", "REFERRAL", "SECURITY", "USER_BILLING"));
        if (billing.getOperatorUserIds().contains(userId)) permissions.addAll(Set.of("FINANCE", "PARTNERS"));
        return new Access(!permissions.isEmpty(), Set.copyOf(permissions), List.copyOf(roles));
    }

    public void require(Long userId, String permission) {
        if (!access(userId).permissions().contains(permission)) throw BizException.of(ErrorCode.FORBIDDEN, "没有此项后台操作权限");
    }
    public void requireOneOf(Long userId, String... permissions) {
        if (Collections.disjoint(access(userId).permissions(), Set.of(permissions))) throw BizException.of(ErrorCode.FORBIDDEN, "没有此项后台操作权限");
    }
    public void requireAny(Long userId) {
        if (!access(userId).operator()) throw BizException.of(ErrorCode.FORBIDDEN, "没有平台运营权限，请联系平台开通");
    }

    @Transactional(readOnly=true)
    public List<Assignment> assignments(Long actor) {
        require(actor, "SECURITY");
        @SuppressWarnings("unchecked") List<Number> ids = em.createNativeQuery("SELECT DISTINCT user_id FROM platform_roles").getResultList();
        Set<Long> assigned = new HashSet<>(); ids.forEach(n -> assigned.add(n.longValue()));
        assigned.addAll(platform.getOperatorUserIds()); assigned.addAll(billing.getOperatorUserIds());
        return directory.users().stream().filter(u -> assigned.contains(u.id())).map(u -> new Assignment(u.uid(), u.name(), access(u.id()).roles(),
                platform.getOperatorUserIds().contains(u.id()), billing.getOperatorUserIds().contains(u.id()))).toList();
    }

    @Transactional
    public List<Assignment> assign(Long actor, RoleRequest input) {
        require(actor, "SECURITY");
        em.createNativeQuery("SELECT true FROM pg_advisory_xact_lock(hashtextextended('backoffice-roles',0))").getSingleResult();
        require(actor, "SECURITY");
        var user = directory.userByUid(input.uid()).filter(u -> "ACTIVE".equals(u.status()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "未找到有效的用户账号"));
        if (user.id().equals(actor)) throw BizException.of(ErrorCode.BAD_REQUEST, "请由另一位管理员调整您的权限");
        // Serialize concurrent edits to the same account; bootstrap authority is kept in server configuration.
        em.createNativeQuery("SELECT id FROM users WHERE id=:id FOR UPDATE").setParameter("id", user.id()).getSingleResult();
        em.createNativeQuery("DELETE FROM platform_roles WHERE user_id=:id").setParameter("id", user.id()).executeUpdate();
        for (String role : new HashSet<>(input.roles())) {
            if (!ROLES.containsKey(role)) throw BizException.of(ErrorCode.BAD_REQUEST, "管理员角色无效");
            em.createNativeQuery("INSERT INTO platform_roles(user_id,role,assigned_by) VALUES (:id,:role,:actor)")
                    .setParameter("id", user.id()).setParameter("role", role).setParameter("actor", actor).executeUpdate();
        }
        return assignments(actor);
    }
}
