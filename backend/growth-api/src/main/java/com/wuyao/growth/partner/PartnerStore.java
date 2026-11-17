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
 * 改成员、分烟火都先锁服务商那一行，同一家服务商的这些写入排队执行。
 */
@Component
@RequiredArgsConstructor
class PartnerStore {
    private final EntityManager em;

    /** teamTenantId 是服务商的团队账户（负责人的一方志商户），烟火都记在它的账本里。 */
    record Row(Long id, String name, String contact, String status, Long teamTenantId, Instant createdAt) {}
    /**
     * 服务商的一个登录账号。role 为 OWNER（负责人，全部权限）或 MEMBER（成员，按 perms）；
     * monthlyCap 是成员每月最多分出的烟火，空表示不限；displayName 是团队里的称呼，空表示还没填。
     */
    record MemberRow(Long userId, String role, Perms perms, Long monthlyCap, String displayName, Instant createdAt) {
        boolean owner() { return "OWNER".equals(role); }
        Perms effective() { return owner() ? Perms.ALL : perms; }
    }
    /** 成员的几项权限：商户、激活码、分配烟火、财务查看、团队管理。 */
    record Perms(boolean tenants, boolean codes, boolean distribute, boolean finance, boolean team) {
        static final Perms ALL = new Perms(true, true, true, true, true);
    }
    /** 当前登录账号所属的服务商和他在这家的身份。 */
    record Access(Row partner, MemberRow member) {
        Long userId() { return member.userId(); }
        boolean owner() { return member.owner(); }
        Perms perms() { return member.effective(); }
    }
    /** changedBy：按 UID 添加的是添加人，兑换激活码归属的为空，平台改的是平台运营。 */
    record Binding(Long tenantId, Long partnerId, String source, Instant boundAt, Long changedBy) {}
    /** 服务商流水：GRANT 平台发放（V84 起直接进团队账户，availableAfter 为空），TRANSFER 是 V84 以前从可分配烟火分出去的。 */
    record LedgerRow(Long id, String type, long delta, Long availableAfter, Long tenantId, String note, Long actorUserId, Instant createdAt) {}
    /** 一笔分配：source 为 POOL（V84 以前的可分配烟火）或 ACCOUNT（团队账户）；recipientType 为 TENANT（V84 以前还有 BD）。 */
    record Distribution(Long id, String requestKey, String source, String recipientType, Long recipientTenantId, Long recipientUserId,
                        long credits, long gift, long purchased, String note, Long actorUserId, String status, Instant createdAt) {}
    /** 服务商烟火流水按北京时间日期、类型、商户合计。 */
    record CreditDay(String date, String type, Long tenantId, long delta) {}
    /** 一个激活码在看板里要用到的时间点；renewal 表示直接续期生成的码。 */
    record CodeEvent(Long createdBy, Instant createdAt, Instant redeemedAt, Instant voidedAt, boolean renewal) {}
    /** 某个账号在这家服务商生成的激活码：总数、已兑换、已作废、其中直接续期。 */
    record CodeStats(long generated, long redeemed, long voided, long renewals) {}

    private static final String PARTNER_COLUMNS = "p.id, p.name, p.contact, p.status, p.team_tenant_id, p.created_at";
    private static final String MEMBER_COLUMNS = "user_id, role, can_tenants, can_codes, can_distribute, can_finance, can_team, "
            + "distribute_monthly_cap, display_name, created_at";

    @Transactional(readOnly = true)
    public Optional<Access> accessOfUser(Long userId) {
        var partner = rows("SELECT " + PARTNER_COLUMNS + " FROM partners p JOIN partner_members m ON m.partner_id = p.id WHERE m.user_id = :user",
                Map.of("user", userId)).stream().findFirst();
        return partner.flatMap(p -> member(p.id(), userId).map(m -> new Access(p, m)));
    }

    /** 已删除的服务商（V85）查不到，平台和服务商后台都当它不存在。 */
    @Transactional(readOnly = true)
    public Optional<Row> partner(Long id) {
        return rows("SELECT " + PARTNER_COLUMNS + " FROM partners p WHERE p.id = :id AND p.status <> 'DELETED'", Map.of("id", id)).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Row> partners() { return rows("SELECT " + PARTNER_COLUMNS + " FROM partners p WHERE p.status <> 'DELETED' ORDER BY p.id DESC", Map.of()); }

    @Transactional
    public Row lockPartner(Long id) {
        return rows("SELECT " + PARTNER_COLUMNS + " FROM partners p WHERE p.id = :id AND p.status <> 'DELETED' FOR UPDATE", Map.of("id", id)).stream().findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "服务商不存在"));
    }

    @Transactional
    public Long createPartner(String name, String contact, Long teamTenantId, Long actor) {
        var query = em.createNativeQuery("INSERT INTO partners(name, contact, team_tenant_id, created_by) VALUES (:name, :contact, :team, :actor) RETURNING id")
                .setParameter("name", name).setParameter("team", teamTenantId).setParameter("actor", actor);
        typed(query).setParameter("contact", contact, String.class);
        return num(query.getSingleResult());
    }

    /** 平台改服务商的名称和联系方式；名称就是服务商在服务商后台里显示的名字。 */
    @Transactional
    public void rename(Long id, String name, String contact) {
        var update = em.createNativeQuery("UPDATE partners SET name = :name, contact = :contact, updated_at = now() WHERE id = :id")
                .setParameter("name", name).setParameter("id", id);
        typed(update).setParameter("contact", contact, String.class);
        update.executeUpdate();
    }

    @Transactional
    public void setStatus(Long id, String status) {
        em.createNativeQuery("UPDATE partners SET status = :status, updated_at = now() WHERE id = :id")
                .setParameter("status", status).setParameter("id", id).executeUpdate();
    }

    /** 负责人在前，其余按加入先后。 */
    @Transactional(readOnly = true)
    public List<MemberRow> members(Long partnerId) {
        List<?> rows = em.createNativeQuery("SELECT " + MEMBER_COLUMNS + " FROM partner_members WHERE partner_id = :id ORDER BY role = 'OWNER' DESC, created_at, user_id")
                .setParameter("id", partnerId).getResultList();
        return rows.stream().map(r -> memberRow((Object[]) r)).toList();
    }

    /** 该账号在这家服务商的身份；不是这家的账号时为空。 */
    @Transactional(readOnly = true)
    public Optional<MemberRow> member(Long partnerId, Long userId) {
        List<?> rows = em.createNativeQuery("SELECT " + MEMBER_COLUMNS + " FROM partner_members WHERE partner_id = :partner AND user_id = :user")
                .setParameter("partner", partnerId).setParameter("user", userId).getResultList();
        return rows.stream().map(r -> memberRow((Object[]) r)).findFirst();
    }

    private static MemberRow memberRow(Object[] r) {
        return new MemberRow(num(r[0]), (String) r[1], new Perms((Boolean) r[2], (Boolean) r[3], (Boolean) r[4], (Boolean) r[5], (Boolean) r[6]),
                r[7] == null ? null : num(r[7]), (String) r[8], instant(r[9]));
    }

    /** 改成员的权限和每月分配上限（负责人的这几列不起作用）。 */
    @Transactional
    public void setPermissions(Long partnerId, Long userId, Perms perms, Long monthlyCap) {
        var update = em.createNativeQuery("""
                UPDATE partner_members SET can_tenants = :t, can_codes = :c, can_distribute = :d, can_finance = :f, can_team = :m,
                    distribute_monthly_cap = :cap WHERE partner_id = :partner AND user_id = :user""")
                .setParameter("t", perms.tenants()).setParameter("c", perms.codes()).setParameter("d", perms.distribute())
                .setParameter("f", perms.finance()).setParameter("m", perms.team())
                .setParameter("partner", partnerId).setParameter("user", userId);
        typed(update).setParameter("cap", monthlyCap, Long.class);
        update.executeUpdate();
    }

    /** 团队里已经有别人用这个称呼。 */
    @Transactional(readOnly = true)
    public boolean displayNameTaken(Long partnerId, Long userId, String name) {
        return !em.createNativeQuery("SELECT 1 FROM partner_members WHERE partner_id = :partner AND display_name = :name AND user_id <> :user")
                .setParameter("partner", partnerId).setParameter("name", name).setParameter("user", userId).getResultList().isEmpty();
    }

    @Transactional
    public void setDisplayName(Long partnerId, Long userId, String name) {
        em.createNativeQuery("UPDATE partner_members SET display_name = :name WHERE partner_id = :partner AND user_id = :user")
                .setParameter("name", name).setParameter("partner", partnerId).setParameter("user", userId).executeUpdate();
    }

    /** @return 该账号已属于的服务商；为空表示这次加上了（已在这家的账号角色不变） */
    @Transactional
    public Optional<Long> addMember(Long partnerId, Long userId, String role, Long actor) {
        em.createNativeQuery("INSERT INTO partner_members(user_id, partner_id, role, created_by) VALUES (:user, :partner, :role, :actor) ON CONFLICT (user_id) DO NOTHING")
                .setParameter("user", userId).setParameter("partner", partnerId).setParameter("role", role).setParameter("actor", actor).executeUpdate();
        Long owner = num(em.createNativeQuery("SELECT partner_id FROM partner_members WHERE user_id = :user").setParameter("user", userId).getSingleResult());
        return owner.equals(partnerId) ? Optional.empty() : Optional.of(owner);
    }

    @Transactional
    public void setRole(Long partnerId, Long userId, String role) {
        em.createNativeQuery("UPDATE partner_members SET role = :role WHERE partner_id = :partner AND user_id = :user")
                .setParameter("role", role).setParameter("partner", partnerId).setParameter("user", userId).executeUpdate();
    }

    @Transactional
    public int removeMember(Long partnerId, Long userId) {
        return em.createNativeQuery("DELETE FROM partner_members WHERE partner_id = :partner AND user_id = :user")
                .setParameter("partner", partnerId).setParameter("user", userId).executeUpdate();
    }

    /**
     * 软删除：标记 DELETED，移出全部登录账号（可以再加入别家），名下商户解除归属。流水、分配、跟进和激活码记录都保留。
     * 调用方先锁服务商、作废没兑换的激活码，再调这里，这样作废前刚兑换出来的归属也会一起解除。
     */
    @Transactional
    public void delete(Long partnerId) {
        em.createNativeQuery("UPDATE partners SET status = 'DELETED', updated_at = now() WHERE id = :id").setParameter("id", partnerId).executeUpdate();
        em.createNativeQuery("DELETE FROM partner_members WHERE partner_id = :id").setParameter("id", partnerId).executeUpdate();
        em.createNativeQuery("DELETE FROM partner_tenants WHERE partner_id = :id").setParameter("id", partnerId).executeUpdate();
    }

    /** 还没到账的分配笔数（团队账户已扣、商户还没收到）。 */
    @Transactional(readOnly = true)
    public long pendingDistributions(Long partnerId) {
        return num(em.createNativeQuery("SELECT count(*) FROM partner_distributions WHERE partner_id = :id AND status = 'PENDING'")
                .setParameter("id", partnerId).getSingleResult());
    }

    private static final String BINDING_COLUMNS = "tenant_id, partner_id, source, bound_at, changed_by";

    @Transactional(readOnly = true)
    public Optional<Binding> binding(Long tenantId) {
        List<?> rows = em.createNativeQuery("SELECT " + BINDING_COLUMNS + " FROM partner_tenants WHERE tenant_id = :t")
                .setParameter("t", tenantId).getResultList();
        return rows.stream().map(r -> binding((Object[]) r)).findFirst();
    }

    /** 第一次归属为准：已经归属任何服务商时不改。@return 当前的归属 */
    @Transactional
    public Binding bindIfAbsent(Long tenantId, Long partnerId, String source, Long actor) {
        var insert = em.createNativeQuery("""
                INSERT INTO partner_tenants(tenant_id, partner_id, source, changed_by) VALUES (:t, :p, :s, :a)
                ON CONFLICT (tenant_id) DO NOTHING""")
                .setParameter("t", tenantId).setParameter("p", partnerId).setParameter("s", source);
        typed(insert).setParameter("a", actor, Long.class);
        insert.executeUpdate();
        return binding(tenantId).orElseThrow();
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
                    changed_by = EXCLUDED.changed_by""")
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

    /** 记一条平台发放（烟火已进团队账户），调用方已确认 event_key 没有记过。 */
    @Transactional
    public void recordGrant(Long partnerId, String key, long credits, String note, Long actor) {
        em.createNativeQuery("""
                INSERT INTO partner_credit_ledger(partner_id, event_key, type, delta, tenant_id, note, actor_user_id)
                VALUES (:p, :k, 'GRANT', :delta, NULL, :note, :a)""")
                .setParameter("p", partnerId).setParameter("k", key).setParameter("delta", credits).setParameter("note", note)
                .setParameter("a", actor).executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<LedgerRow> ledger(Long partnerId, int page, int size) {
        List<?> rows = em.createNativeQuery("""
                SELECT id, type, delta, available_after, tenant_id, note, actor_user_id, created_at FROM partner_credit_ledger
                WHERE partner_id = :p ORDER BY id DESC LIMIT :limit OFFSET :offset""")
                .setParameter("p", partnerId).setParameter("limit", size).setParameter("offset", (long) page * size).getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new LedgerRow(num(r[0]), (String) r[1], num(r[2]), r[3] == null ? null : num(r[3]),
                r[4] == null ? null : num(r[4]), (String) r[5], num(r[6]), instant(r[7]))).toList();
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

    private static final String DISTRIBUTION_COLUMNS = "id, request_key, source, recipient_type, recipient_tenant_id, recipient_user_id, "
            + "credits, gift_credits, purchased_credits, note, actor_user_id, status, created_at";

    private static Distribution distribution(Object[] r) {
        return new Distribution(num(r[0]), (String) r[1], (String) r[2], (String) r[3], num(r[4]), r[5] == null ? null : num(r[5]),
                num(r[6]), num(r[7]), num(r[8]), (String) r[9], num(r[10]), (String) r[11], instant(r[12]));
    }

    @Transactional(readOnly = true)
    public Optional<Distribution> distribution(Long partnerId, Long id) {
        List<?> rows = em.createNativeQuery("SELECT " + DISTRIBUTION_COLUMNS + " FROM partner_distributions WHERE id = :id AND partner_id = :p")
                .setParameter("id", id).setParameter("p", partnerId).getResultList();
        return rows.stream().map(r -> distribution((Object[]) r)).findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Distribution> distributionByKey(String key) {
        List<?> rows = em.createNativeQuery("SELECT " + DISTRIBUTION_COLUMNS + " FROM partner_distributions WHERE request_key = :k")
                .setParameter("k", key).getResultList();
        return rows.stream().map(r -> distribution((Object[]) r)).findFirst();
    }

    /** 记一笔分配（已扣过来源）；同一 request_key 已有时不动。 */
    @Transactional
    public void recordDistribution(Long partnerId, String key, String source, String recipientType, Long recipientTenantId, Long recipientUserId,
                                   long gift, long purchased, String note, Long actor) {
        var insert = em.createNativeQuery("""
                INSERT INTO partner_distributions(partner_id, request_key, source, recipient_type, recipient_tenant_id, recipient_user_id,
                    credits, gift_credits, purchased_credits, note, actor_user_id)
                VALUES (:p, :k, :s, :rt, :t, :u, :c, :g, :pc, :note, :a) ON CONFLICT (request_key) DO NOTHING""")
                .setParameter("p", partnerId).setParameter("k", key).setParameter("s", source).setParameter("rt", recipientType)
                .setParameter("t", recipientTenantId).setParameter("c", gift + purchased).setParameter("g", gift)
                .setParameter("pc", purchased).setParameter("a", actor);
        typed(insert).setParameter("u", recipientUserId, Long.class).setParameter("note", note, String.class);
        insert.executeUpdate();
    }

    @Transactional
    public void completeDistribution(String key) {
        em.createNativeQuery("UPDATE partner_distributions SET status = 'DONE', completed_at = now() WHERE request_key = :k AND status = 'PENDING'")
                .setParameter("k", key).executeUpdate();
    }

    /** 分配记录，新的在前；各筛选为空时不限，actor 只看某个人分出去的。 */
    @Transactional(readOnly = true)
    public List<Distribution> distributions(Long partnerId, String recipientType, String source, Long tenantId, Long actor,
                                            Instant since, int page, int size) {
        var query = em.createNativeQuery("SELECT " + DISTRIBUTION_COLUMNS + " FROM partner_distributions" + distributionWhere()
                + " ORDER BY id DESC LIMIT :limit OFFSET :offset");
        bindDistributionFilters(query, partnerId, recipientType, source, tenantId, actor, since);
        List<?> rows = query.setParameter("limit", size).setParameter("offset", (long) page * size).getResultList();
        return rows.stream().map(r -> distribution((Object[]) r)).toList();
    }

    @Transactional(readOnly = true)
    public long distributionCount(Long partnerId, String recipientType, String source, Long tenantId, Long actor, Instant since) {
        var query = em.createNativeQuery("SELECT count(*) FROM partner_distributions" + distributionWhere());
        bindDistributionFilters(query, partnerId, recipientType, source, tenantId, actor, since);
        return num(query.getSingleResult());
    }

    /** 某个人从 since 起分出去的烟火合计（含还没到账的），用来卡每月上限。 */
    @Transactional(readOnly = true)
    public long distributedBy(Long partnerId, Long actor, Instant since) {
        return num(em.createNativeQuery("SELECT COALESCE(sum(credits), 0) FROM partner_distributions WHERE partner_id = :p AND actor_user_id = :a AND created_at >= :since")
                .setParameter("p", partnerId).setParameter("a", actor).setParameter("since", Timestamp.from(since)).getSingleResult());
    }

    private static String distributionWhere() {
        // 前面留一个空格：文本块会去掉开头的缩进，拼在 FROM 表名后面要隔开。
        return " " + """
                WHERE partner_id = :p AND (:rt = '' OR recipient_type = :rt) AND (:s = '' OR source = :s)
                   AND (:t = 0 OR recipient_tenant_id = :t) AND (:u = 0 OR actor_user_id = :u) AND created_at >= :since""";
    }

    private static void bindDistributionFilters(jakarta.persistence.Query query, Long partnerId, String recipientType, String source,
                                                Long tenantId, Long actor, Instant since) {
        query.setParameter("p", partnerId).setParameter("rt", recipientType == null ? "" : recipientType)
                .setParameter("s", source == null ? "" : source).setParameter("t", tenantId == null ? 0L : tenantId)
                .setParameter("u", actor == null ? 0L : actor).setParameter("since", Timestamp.from(since == null ? Instant.EPOCH : since));
    }

    @Transactional(readOnly = true)
    public long ledgerCount(Long partnerId) {
        return num(em.createNativeQuery("SELECT count(*) FROM partner_credit_ledger WHERE partner_id = :p").setParameter("p", partnerId).getSingleResult());
    }

    private List<Row> rows(String sql, Map<String, Object> params) {
        var query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        List<?> rows = query.getResultList();
        return rows.stream().map(r -> (Object[]) r).map(r -> new Row(num(r[0]), (String) r[1], (String) r[2], (String) r[3],
                r[4] == null ? null : num(r[4]), instant(r[5]))).toList();
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
