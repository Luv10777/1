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
    record MemberRow(Long userId, String role, Instant createdAt) {}
    /** 当前登录账号所属的服务商和角色（OWNER 负责人、BD）。 */
    record Access(Row partner, Long userId, String role) {
        boolean owner() { return "OWNER".equals(role); }
    }
    record Binding(Long tenantId, Long partnerId, String source, Instant boundAt, Long bdUserId) {}
    record LedgerRow(Long id, String type, long delta, long availableAfter, Long tenantId, String note, Instant createdAt) {}
    /** 服务商烟火流水按北京时间日期、类型、商户合计。 */
    record CreditDay(String date, String type, Long tenantId, long delta) {}
    /** 一个激活码在看板里要用到的时间点；renewal 表示直接续期生成的码。 */
    record CodeEvent(Long createdBy, Instant createdAt, Instant redeemedAt, Instant voidedAt, boolean renewal) {}
    /** 某个账号在这家服务商生成的激活码：总数、已兑换、已作废、其中直接续期。 */
    record CodeStats(long generated, long redeemed, long voided, long renewals) {}

    private static final String PARTNER_COLUMNS = "p.id, p.name, p.contact, p.status, p.credits_available, p.created_at";

    @Transactional(readOnly = true)
    public Optional<Access> accessOfUser(Long userId) {
        var partner = rows("SELECT " + PARTNER_COLUMNS + " FROM partners p JOIN partner_members m ON m.partner_id = p.id WHERE m.user_id = :user",
                Map.of("user", userId)).stream().findFirst();
        return partner.map(p -> new Access(p, userId, (String) em.createNativeQuery("SELECT role FROM partner_members WHERE user_id = :user")
                .setParameter("user", userId).getSingleResult()));
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
        List<?> rows = em.createNativeQuery("SELECT user_id, role, created_at FROM partner_members WHERE partner_id = :id ORDER BY role DESC, created_at, user_id")
                .setParameter("id", partnerId).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new MemberRow(num(r[0]), (String) r[1], instant(r[2]))).toList();
    }

    /** @return 该账号已属于的服务商；为空表示这次加上了（已在这家的账号角色不变） */
    @Transactional
    public Optional<Long> addMember(Long partnerId, Long userId, String role, Long actor) {
        em.createNativeQuery("INSERT INTO partner_members(user_id, partner_id, role, created_by) VALUES (:user, :partner, :role, :actor) ON CONFLICT (user_id) DO NOTHING")
                .setParameter("user", userId).setParameter("partner", partnerId).setParameter("role", role).setParameter("actor", actor).executeUpdate();
        Long owner = num(em.createNativeQuery("SELECT partner_id FROM partner_members WHERE user_id = :user").setParameter("user", userId).getSingleResult());
        return owner.equals(partnerId) ? Optional.empty() : Optional.of(owner);
    }

    /** 移除登录账号；它名下的商户回到负责人直接管。 */
    @Transactional
    public int removeMember(Long partnerId, Long userId) {
        em.createNativeQuery("UPDATE partner_tenants SET bd_user_id = NULL WHERE partner_id = :partner AND bd_user_id = :user")
                .setParameter("partner", partnerId).setParameter("user", userId).executeUpdate();
        return em.createNativeQuery("DELETE FROM partner_members WHERE partner_id = :partner AND user_id = :user")
                .setParameter("partner", partnerId).setParameter("user", userId).executeUpdate();
    }

    /** 该账号在这家服务商的角色；不是这家的账号时为空。 */
    @Transactional(readOnly = true)
    public Optional<String> role(Long partnerId, Long userId) {
        List<?> rows = em.createNativeQuery("SELECT role FROM partner_members WHERE partner_id = :partner AND user_id = :user")
                .setParameter("partner", partnerId).setParameter("user", userId).getResultList();
        return rows.stream().map(r -> (String) r).findFirst();
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

    private static final String BINDING_COLUMNS = "tenant_id, partner_id, source, bound_at, bd_user_id";

    @Transactional(readOnly = true)
    public Optional<Binding> binding(Long tenantId) {
        List<?> rows = em.createNativeQuery("SELECT " + BINDING_COLUMNS + " FROM partner_tenants WHERE tenant_id = :t")
                .setParameter("t", tenantId).getResultList();
        return rows.stream().map(r -> binding((Object[]) r)).findFirst();
    }

    /** 第一次归属为准：已经归属任何服务商时不改（BD 也不改）。@return 当前的归属 */
    @Transactional
    public Binding bindIfAbsent(Long tenantId, Long partnerId, String source, Long actor, Long bdUserId) {
        var insert = em.createNativeQuery("""
                INSERT INTO partner_tenants(tenant_id, partner_id, source, changed_by, bd_user_id) VALUES (:t, :p, :s, :a, :bd)
                ON CONFLICT (tenant_id) DO NOTHING""")
                .setParameter("t", tenantId).setParameter("p", partnerId).setParameter("s", source);
        typed(insert).setParameter("a", actor, Long.class).setParameter("bd", bdUserId, Long.class);
        insert.executeUpdate();
        return binding(tenantId).orElseThrow();
    }

    /** 负责人把名下商户调给某个 BD；bdUserId 为空表示收回由负责人直接管。 */
    @Transactional
    public void setBd(Long tenantId, Long partnerId, Long bdUserId) {
        var update = em.createNativeQuery("UPDATE partner_tenants SET bd_user_id = :bd WHERE tenant_id = :t AND partner_id = :p")
                .setParameter("t", tenantId).setParameter("p", partnerId);
        typed(update).setParameter("bd", bdUserId, Long.class);
        update.executeUpdate();
    }

    /** 移除 BD 前把他名下的商户整体转给另一个 BD。 */
    @Transactional
    public void moveBdTenants(Long partnerId, Long from, Long to) {
        em.createNativeQuery("UPDATE partner_tenants SET bd_user_id = :to WHERE partner_id = :p AND bd_user_id = :from")
                .setParameter("to", to).setParameter("p", partnerId).setParameter("from", from).executeUpdate();
    }

    /** 每个登录账号生成的激活码统计，按生成人分组。 */
    @Transactional(readOnly = true)
    public Map<Long, CodeStats> codeStats(Long partnerId) {
        List<?> rows = em.createNativeQuery("""
                SELECT created_by, count(*), count(redeemed_at), count(voided_at), count(*) FILTER (WHERE batch_key LIKE 'partner-renew-%')
                FROM billing_redeem_codes WHERE partner_id = :p GROUP BY created_by""").setParameter("p", partnerId).getResultList();
        Map<Long, CodeStats> stats = new HashMap<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            stats.put(num(r[0]), new CodeStats(num(r[1]), num(r[2]), num(r[3]), num(r[4])));
        }
        return stats;
    }

    /** 激活码的生成人（BD 生成的码兑换后归到这个 BD）。 */
    @Transactional(readOnly = true)
    public Optional<Long> codeCreator(Long codeId) {
        List<?> rows = em.createNativeQuery("SELECT created_by FROM billing_redeem_codes WHERE id = :id").setParameter("id", codeId).getResultList();
        return rows.stream().map(PartnerStore::num).findFirst();
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
                ON CONFLICT (tenant_id) DO UPDATE SET partner_id = EXCLUDED.partner_id, source = 'PLATFORM', bound_at = now(),
                    changed_by = EXCLUDED.changed_by, bd_user_id = NULL""")
                .setParameter("t", tenantId).setParameter("p", partnerId).setParameter("a", actor).executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<Binding> tenants(Long partnerId) {
        List<?> rows = em.createNativeQuery("SELECT " + BINDING_COLUMNS + " FROM partner_tenants WHERE partner_id = :p ORDER BY bound_at DESC, tenant_id DESC")
                .setParameter("p", partnerId).getResultList();
        return rows.stream().map(r -> binding((Object[]) r)).toList();
    }

    private static Binding binding(Object[] r) {
        return new Binding(num(r[0]), num(r[1]), (String) r[2], instant(r[3]), r[4] == null ? null : num(r[4]));
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
    public List<CreditDay> creditDays(Long partnerId, Instant since) {
        List<?> rows = em.createNativeQuery("""
                SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD'), type, tenant_id, sum(delta)
                FROM partner_credit_ledger WHERE partner_id = :p AND created_at >= :since GROUP BY 1, 2, 3""")
                .setParameter("p", partnerId).setParameter("since", Timestamp.from(since)).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new CreditDay((String) r[0], (String) r[1],
                r[2] == null ? null : num(r[2]), num(r[3]))).toList();
    }

    /** 这段时间里生成、兑换或作废过的本服务商激活码。 */
    @Transactional(readOnly = true)
    public List<CodeEvent> codeEvents(Long partnerId, Instant since) {
        List<?> rows = em.createNativeQuery("""
                SELECT created_by, created_at, redeemed_at, voided_at, batch_key LIKE 'partner-renew-%'
                FROM billing_redeem_codes WHERE partner_id = :p AND (created_at >= :since OR redeemed_at >= :since OR voided_at >= :since)""")
                .setParameter("p", partnerId).setParameter("since", Timestamp.from(since)).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new CodeEvent(num(r[0]), instant(r[1]),
                r[2] == null ? null : instant(r[2]), r[3] == null ? null : instant(r[3]), Boolean.TRUE.equals(r[4]))).toList();
    }

    /** 这家服务商分给某个商户的烟火流水，新的在前。 */
    @Transactional(readOnly = true)
    public List<LedgerRow> tenantLedger(Long partnerId, Long tenantId, int limit) {
        List<?> rows = em.createNativeQuery("""
                SELECT id, type, delta, available_after, tenant_id, note, created_at FROM partner_credit_ledger
                WHERE partner_id = :p AND tenant_id = :t ORDER BY id DESC LIMIT :limit""")
                .setParameter("p", partnerId).setParameter("t", tenantId).setParameter("limit", limit).getResultList();
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
