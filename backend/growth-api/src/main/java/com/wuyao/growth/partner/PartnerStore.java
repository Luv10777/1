package com.wuyao.growth.partner;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 服务商相关的几张平台级表（不开行级隔离，见 V79）。只在服务层使用：调用方先核对平台运营或服务商身份。
 * 改余额、改额度都先锁服务商或额度那一行，同一家服务商的写入排队执行。
 */
@Component
@RequiredArgsConstructor
class PartnerStore {
    private final EntityManager em;

    record Row(Long id, String name, String contact, String status, long creditsAvailable, Instant createdAt) {
        PartnerDtos.Partner view() { return new PartnerDtos.Partner(id, name, contact, status, creditsAvailable, createdAt); }
    }
    record QuotaRow(String planCode, int quota, int used) {}
    record MemberRow(Long userId, Instant createdAt) {}
    record Binding(Long tenantId, Long partnerId, String source, Instant boundAt) {}
    record LedgerRow(Long id, String type, long delta, long availableAfter, Long tenantId, String note, Instant createdAt) {}

    private static final String PARTNER_COLUMNS = "p.id, p.name, p.contact, p.status, p.credits_available, p.created_at";

    @Transactional(readOnly = true)
    public Optional<Row> partnerOfUser(Long userId) {
        return rows("SELECT " + PARTNER_COLUMNS + " FROM partners p JOIN partner_members m ON m.partner_id = p.id WHERE m.user_id = :user",
                Map.of("user", userId)).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Row> partner(Long id) {
        return rows("SELECT " + PARTNER_COLUMNS + " FROM partners p WHERE p.id = :id", Map.of("id", id)).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Row> partners() { return rows("SELECT " + PARTNER_COLUMNS + " FROM partners p ORDER BY p.id DESC", Map.of()); }

    @Transactional
    public Row lockPartner(Long id) {
        return rows("SELECT " + PARTNER_COLUMNS + " FROM partners p WHERE p.id = :id FOR UPDATE", Map.of("id", id)).stream().findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "服务商不存在"));
    }

    @Transactional
    public Long createPartner(String name, String contact, Long actor) {
        var query = em.createNativeQuery("INSERT INTO partners(name, contact, created_by) VALUES (:name, :contact, :actor) RETURNING id")
                .setParameter("name", name).setParameter("actor", actor);
        typed(query).setParameter("contact", contact, String.class);
        return num(query.getSingleResult());
    }

    @Transactional
    public void setStatus(Long id, String status) {
        em.createNativeQuery("UPDATE partners SET status = :status, updated_at = now() WHERE id = :id")
                .setParameter("status", status).setParameter("id", id).executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<MemberRow> members(Long partnerId) {
        List<?> rows = em.createNativeQuery("SELECT user_id, created_at FROM partner_members WHERE partner_id = :id ORDER BY created_at, user_id")
                .setParameter("id", partnerId).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new MemberRow(num(r[0]), instant(r[1]))).toList();
    }

    /** @return 该账号已属于的服务商；为空表示这次加上了 */
    @Transactional
    public Optional<Long> addMember(Long partnerId, Long userId, Long actor) {
        em.createNativeQuery("INSERT INTO partner_members(user_id, partner_id, created_by) VALUES (:user, :partner, :actor) ON CONFLICT (user_id) DO NOTHING")
                .setParameter("user", userId).setParameter("partner", partnerId).setParameter("actor", actor).executeUpdate();
        Long owner = num(em.createNativeQuery("SELECT partner_id FROM partner_members WHERE user_id = :user").setParameter("user", userId).getSingleResult());
        return owner.equals(partnerId) ? Optional.empty() : Optional.of(owner);
    }

    @Transactional
    public int removeMember(Long partnerId, Long userId) {
        return em.createNativeQuery("DELETE FROM partner_members WHERE partner_id = :partner AND user_id = :user")
                .setParameter("partner", partnerId).setParameter("user", userId).executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<QuotaRow> quotas(Long partnerId) {
        List<?> rows = em.createNativeQuery("SELECT plan_code, quota, used FROM partner_code_quotas WHERE partner_id = :id ORDER BY plan_code")
                .setParameter("id", partnerId).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new QuotaRow((String) r[0], num(r[1]).intValue(), num(r[2]).intValue())).toList();
    }

    @Transactional
    public Optional<QuotaRow> lockQuota(Long partnerId, String planCode) {
        List<?> rows = em.createNativeQuery("SELECT plan_code, quota, used FROM partner_code_quotas WHERE partner_id = :id AND plan_code = :code FOR UPDATE")
                .setParameter("id", partnerId).setParameter("code", planCode).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new QuotaRow((String) r[0], num(r[1]).intValue(), num(r[2]).intValue())).findFirst();
    }

    /** 设总额度；不能低于已经生成的个数。 */
    @Transactional
    public void setQuota(Long partnerId, String planCode, int quota) {
        var current = lockQuota(partnerId, planCode);
        if (current.isPresent() && quota < current.get().used())
            throw BizException.of(ErrorCode.BAD_REQUEST, "额度不能低于已生成的 " + current.get().used() + " 个");
        em.createNativeQuery("""
                INSERT INTO partner_code_quotas(partner_id, plan_code, quota) VALUES (:id, :code, :quota)
                ON CONFLICT (partner_id, plan_code) DO UPDATE SET quota = EXCLUDED.quota, updated_at = now()""")
                .setParameter("id", partnerId).setParameter("code", planCode).setParameter("quota", quota).executeUpdate();
    }

    @Transactional
    public void useQuota(Long partnerId, String planCode, int quantity) {
        em.createNativeQuery("UPDATE partner_code_quotas SET used = used + :n, updated_at = now() WHERE partner_id = :id AND plan_code = :code")
                .setParameter("n", quantity).setParameter("id", partnerId).setParameter("code", planCode).executeUpdate();
    }

    @Transactional(readOnly = true)
    public Optional<Binding> binding(Long tenantId) {
        List<?> rows = em.createNativeQuery("SELECT tenant_id, partner_id, source, bound_at FROM partner_tenants WHERE tenant_id = :t")
                .setParameter("t", tenantId).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new Binding(num(r[0]), num(r[1]), (String) r[2], instant(r[3]))).findFirst();
    }

    /** 第一次归属为准：已经归属任何服务商时不改。@return 当前归属的服务商 */
    @Transactional
    public Long bindIfAbsent(Long tenantId, Long partnerId, String source, Long actor) {
        var insert = em.createNativeQuery("INSERT INTO partner_tenants(tenant_id, partner_id, source, changed_by) VALUES (:t, :p, :s, :a) ON CONFLICT (tenant_id) DO NOTHING")
                .setParameter("t", tenantId).setParameter("p", partnerId).setParameter("s", source);
        typed(insert).setParameter("a", actor, Long.class);
        insert.executeUpdate();
        return num(em.createNativeQuery("SELECT partner_id FROM partner_tenants WHERE tenant_id = :t").setParameter("t", tenantId).getSingleResult());
    }

    /** 平台改归属；partnerId 为空时解除。 */
    @Transactional
    public void reassign(Long tenantId, Long partnerId, Long actor) {
        if (partnerId == null) {
            em.createNativeQuery("DELETE FROM partner_tenants WHERE tenant_id = :t").setParameter("t", tenantId).executeUpdate();
            return;
        }
        em.createNativeQuery("""
                INSERT INTO partner_tenants(tenant_id, partner_id, source, changed_by) VALUES (:t, :p, 'PLATFORM', :a)
                ON CONFLICT (tenant_id) DO UPDATE SET partner_id = EXCLUDED.partner_id, source = 'PLATFORM', bound_at = now(), changed_by = EXCLUDED.changed_by""")
                .setParameter("t", tenantId).setParameter("p", partnerId).setParameter("a", actor).executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<Binding> tenants(Long partnerId) {
        List<?> rows = em.createNativeQuery("SELECT tenant_id, partner_id, source, bound_at FROM partner_tenants WHERE partner_id = :p ORDER BY bound_at DESC, tenant_id DESC")
                .setParameter("p", partnerId).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new Binding(num(r[0]), num(r[1]), (String) r[2], instant(r[3]))).toList();
    }

    @Transactional(readOnly = true)
    public long tenantCount(Long partnerId) {
        return num(em.createNativeQuery("SELECT count(*) FROM partner_tenants WHERE partner_id = :p").setParameter("p", partnerId).getSingleResult());
    }

    @Transactional(readOnly = true)
    public boolean eventExists(String key) {
        return !em.createNativeQuery("SELECT 1 FROM partner_credit_ledger WHERE event_key = :k").setParameter("k", key).getResultList().isEmpty();
    }

    /**
     * 改服务商余额并记一条流水，调用方已锁住服务商那一行并确认 event_key 没有记过。
     * @return 变动后的余额
     */
    @Transactional
    public long move(Row locked, String key, String type, long delta, Long tenantId, String note, Long actor) {
        long after = Math.addExact(locked.creditsAvailable(), delta);
        if (after < 0) throw BizException.of(ErrorCode.INSUFFICIENT_YANHUO, "服务商烟火不足，当前可分配 " + locked.creditsAvailable());
        em.createNativeQuery("UPDATE partners SET credits_available = :after, updated_at = now() WHERE id = :id")
                .setParameter("after", after).setParameter("id", locked.id()).executeUpdate();
        var insert = em.createNativeQuery("""
                INSERT INTO partner_credit_ledger(partner_id, event_key, type, delta, available_after, tenant_id, note, actor_user_id)
                VALUES (:p, :k, :type, :delta, :after, :t, :note, :a)""")
                .setParameter("p", locked.id()).setParameter("k", key).setParameter("type", type).setParameter("delta", delta)
                .setParameter("after", after).setParameter("a", actor);
        typed(insert).setParameter("t", tenantId, Long.class).setParameter("note", note, String.class);
        insert.executeUpdate();
        return after;
    }

    @Transactional(readOnly = true)
    public List<LedgerRow> ledger(Long partnerId, int page, int size) {
        List<?> rows = em.createNativeQuery("""
                SELECT id, type, delta, available_after, tenant_id, note, created_at FROM partner_credit_ledger
                WHERE partner_id = :p ORDER BY id DESC LIMIT :limit OFFSET :offset""")
                .setParameter("p", partnerId).setParameter("limit", size).setParameter("offset", (long) page * size).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new LedgerRow(num(r[0]), (String) r[1], num(r[2]), num(r[3]),
                r[4] == null ? null : num(r[4]), (String) r[5], instant(r[6]))).toList();
    }

    @Transactional(readOnly = true)
    public long ledgerCount(Long partnerId) {
        return num(em.createNativeQuery("SELECT count(*) FROM partner_credit_ledger WHERE partner_id = :p").setParameter("p", partnerId).getSingleResult());
    }

    private List<Row> rows(String sql, Map<String, Object> params) {
        var query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        List<?> rows = query.getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new Row(num(r[0]), (String) r[1], (String) r[2], (String) r[3], num(r[4]), instant(r[5]))).toList();
    }

    /** 可能为空的参数要带上类型绑定，否则 PostgreSQL 推断不出空值的类型。 */
    private static org.hibernate.query.NativeQuery<?> typed(jakarta.persistence.Query query) {
        return query.unwrap(org.hibernate.query.NativeQuery.class);
    }

    private static Long num(Object value) { return ((Number) value).longValue(); }

    private static Instant instant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof Timestamp t) return t.toInstant();
        throw new IllegalStateException("unexpected timestamp " + value);
    }
}
