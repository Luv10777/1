package com.wuyao.growth.billing;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 全平台烟火流水，只读。账本受租户 RLS 约束，逐个商户在各自的租户上下文里查询再合并。
 * 每个商户在时间窗口内最多取 PER_TENANT_LIMIT 条，避免一次读入过多。
 */
@Service
@RequiredArgsConstructor
public class BillingLedgerReport {
    static final Set<String> TYPES = Set.of("RESERVE", "CAPTURE", "RELEASE", "PURCHASE", "GRANT");
    static final Set<String> CAPABILITIES = Set.of("IMAGE", "IMAGE_INSPIRATION", "VIDEO", "VIDEO_ANALYSIS", "VIDEO_PLAN", "COPY", "LIVE");
    private static final int PER_TENANT_LIMIT = 2000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int MAX_DAILY_DAYS = 180;

    private final EntityManager em;
    private final PlatformAccountDirectory directory;
    private final PlatformTransactionManager transactionManager;

    public PageResult<BillingDtos.PlatformLedgerEntry> entries(int page, int size, int days, String type, String capability, String keyword) {
        if (page < 0 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST, "分页参数无效");
        checkFilter(type, TYPES);
        checkFilter(capability, CAPABILITIES);
        Instant since = since(days);
        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> actors = directory.users().stream()
                .collect(Collectors.toMap(PlatformAccountDirectory.PlatformUser::id, u -> u.name() == null ? "" : u.name()));
        var rows = new ArrayList<BillingDtos.PlatformLedgerEntry>();
        for (var tenant : directory.scopes()) {
            if (!key.isEmpty() && !String.valueOf(tenant.name()).toLowerCase(Locale.ROOT).contains(key)
                    && !String.valueOf(tenant.id()).equals(key)) continue;
            rows.addAll(inTenant(tenant.id(), () -> rows(tenant.id(), since, type, capability)).stream()
                    .map(r -> new BillingDtos.PlatformLedgerEntry(((Number) r[0]).longValue(), tenant.id(), tenant.name(),
                            r[1] == null ? null : ((Number) r[1]).longValue(), r[1] == null ? null : actors.get(((Number) r[1]).longValue()),
                            (String) r[2], (String) r[3], (String) r[4], ((Number) r[5]).longValue(), ((Number) r[6]).longValue(),
                            ((Number) r[7]).longValue(), instant(r[8]), (String) r[9],
                            r[10] == null ? null : ((Number) r[10]).longValue(), (String) r[11],
                            r[12] == null ? null : ((Number) r[12]).longValue()))
                    .toList());
        }
        rows.sort(Comparator.comparing(BillingDtos.PlatformLedgerEntry::createdAt).thenComparing(BillingDtos.PlatformLedgerEntry::id).reversed());
        int start = (int) Math.min((long) page * size, rows.size());
        int end = Math.min(start + size, rows.size());
        return new PageResult<>(rows.subList(start, end), page, size, rows.size(), (int) Math.ceil((double) rows.size() / size));
    }

    public BillingDtos.PlatformLedgerSummary summary(int days) {
        Instant since = since(days);
        Instant today = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        long consumed = 0, consumedToday = 0, refunded = 0, purchased = 0, granted = 0;
        Map<String, Long> byCapability = new TreeMap<>();
        for (var tenant : directory.scopes()) {
            List<Object[]> totals = inTenant(tenant.id(), () -> totals(tenant.id(), since, today));
            for (Object[] t : totals) {
                String type = (String) t[0], capability = (String) t[1];
                long amount = ((Number) t[2]).longValue(), todayAmount = ((Number) t[3]).longValue();
                switch (type) {
                    case "CAPTURE" -> {
                        consumed += amount; consumedToday += todayAmount;
                        if (capability != null) byCapability.merge(capability, amount, Long::sum);
                    }
                    case "RELEASE" -> refunded += amount;
                    case "PURCHASE" -> purchased += amount;
                    case "GRANT" -> granted += amount;
                    default -> { }
                }
            }
        }
        return new BillingDtos.PlatformLedgerSummary(days, consumed, consumedToday, refunded, purchased, granted, byCapability);
    }

    /**
     * 最近 days 天（含今天，按北京时间分日）每天的全平台合计，按日期从早到晚，没有流水的日子也给出。
     * 后台用它算任意时段和上一个同长度时段的对比，所以上限是 90 天的两倍。
     */
    public List<BillingDtos.DailyCredits> daily(int days) {
        if (days < 1 || days > MAX_DAILY_DAYS) throw BizException.of(ErrorCode.BAD_REQUEST, "时间范围为 1 至 " + MAX_DAILY_DAYS + " 天");
        LocalDate today = LocalDate.now(ZONE);
        LocalDate first = today.minusDays(days - 1L);
        Instant since = first.atStartOfDay(ZONE).toInstant();
        Map<LocalDate, long[]> totals = new TreeMap<>();
        Map<LocalDate, Map<String, Long>> byCapability = new HashMap<>();
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) {
            totals.put(date, new long[4]);
            byCapability.put(date, new TreeMap<>());
        }
        for (var tenant : directory.scopes()) {
            for (Object[] row : inTenant(tenant.id(), () -> dailyTotals(tenant.id(), since))) {
                LocalDate date = row[0] instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) row[0];
                long[] day = totals.get(date);
                if (day == null) continue;
                String type = (String) row[1], capability = (String) row[2];
                long amount = ((Number) row[3]).longValue();
                switch (type) {
                    case "CAPTURE" -> {
                        day[0] += amount;
                        if (capability != null) byCapability.get(date).merge(capability, amount, Long::sum);
                    }
                    case "RELEASE" -> day[1] += amount;
                    case "PURCHASE" -> day[2] += amount;
                    case "GRANT" -> day[3] += amount;
                    default -> { }
                }
            }
        }
        return totals.entrySet().stream().map(e -> new BillingDtos.DailyCredits(e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[2], e.getValue()[3], byCapability.get(e.getKey()))).toList();
    }

    /** 烟火看板：每个商户的当前余额和累计数，再合计全平台。商户按当前可用烟火从多到少排。 */
    public BillingDtos.CreditOverview overview() {
        long purchased = 0, granted = 0, consumed = 0, available = 0, held = 0;
        Map<String, Long> purchasedBySource = new TreeMap<>(), grantedBySource = new TreeMap<>();
        var merchants = new ArrayList<BillingDtos.MerchantCredits>();
        for (var tenant : directory.scopes()) {
            var data = inTenant(tenant.id(), () -> new TenantCredits(balance(tenant.id()), lifetime(tenant.id())));
            Object[] account = data.account();
            List<Object[]> sources = data.sources();
            long tenantPurchased = 0, tenantGranted = 0, tenantConsumed = 0;
            for (Object[] s : sources) {
                String type = (String) s[0], source = (String) s[1];
                long amount = ((Number) s[2]).longValue();
                switch (type) {
                    case "PURCHASE" -> { tenantPurchased += amount; purchasedBySource.merge(source, amount, Long::sum); }
                    case "GRANT" -> { tenantGranted += amount; grantedBySource.merge(source, amount, Long::sum); }
                    case "CAPTURE" -> tenantConsumed += amount;
                    default -> { }
                }
            }
            long gift = account == null ? 0 : ((Number) account[0]).longValue();
            long bought = account == null ? 0 : ((Number) account[1]).longValue();
            long tenantHeld = account == null ? 0 : ((Number) account[2]).longValue();
            merchants.add(new BillingDtos.MerchantCredits(tenant.id(), tenant.name(), gift, bought, tenantHeld,
                    tenantPurchased, tenantGranted, tenantConsumed));
            purchased += tenantPurchased; granted += tenantGranted; consumed += tenantConsumed;
            available += gift + bought; held += tenantHeld;
        }
        merchants.sort(Comparator.comparingLong((BillingDtos.MerchantCredits m) -> m.giftAvailable() + m.purchasedAvailable()).reversed()
                .thenComparing(BillingDtos.MerchantCredits::tenantId));
        return new BillingDtos.CreditOverview(purchased, granted, consumed, available, held, purchasedBySource, grantedBySource, merchants);
    }

    private record TenantCredits(Object[] account, List<Object[]> sources) {}

    /**
     * 一个商户的使用情况（服务商后台用），在该商户的租户里只读查询。since 之前的扣费只计入首次 / 最近使用时间。
     * 使用次数按扣费关联的预占计，同一次直播分几次扣也只算一次。
     */
    public BillingDtos.TenantUsage usage(Long tenantId, Instant since) {
        return inTenant(tenantId, () -> {
            Object[] facts = (Object[]) em.createNativeQuery("""
                    SELECT (SELECT gift_available + purchased_available FROM billing_accounts WHERE tenant_id = :tenant),
                           (SELECT min(created_at) FROM billing_ledger WHERE tenant_id = :tenant AND type = 'CAPTURE'),
                           (SELECT max(created_at) FROM billing_ledger WHERE tenant_id = :tenant AND type = 'CAPTURE'),
                           (SELECT purchased_delta FROM billing_ledger WHERE tenant_id = :tenant AND type = 'PURCHASE' AND purchased_delta > 0 ORDER BY id DESC LIMIT 1),
                           (SELECT created_at FROM billing_ledger WHERE tenant_id = :tenant AND type = 'PURCHASE' AND purchased_delta > 0 ORDER BY id DESC LIMIT 1)""")
                    .setParameter("tenant", tenantId).getSingleResult();
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery("""
                    SELECT to_char(l.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD'), COALESCE(r.capability, 'OTHER'),
                           COALESCE(SUM(-l.held_delta), 0), COUNT(DISTINCT l.reservation_id)
                    FROM billing_ledger l
                    LEFT JOIN billing_reservations r ON r.id = l.reservation_id AND r.tenant_id = l.tenant_id
                    WHERE l.tenant_id = :tenant AND l.type = 'CAPTURE' AND l.created_at >= :since
                    GROUP BY 1, 2""").setParameter("tenant", tenantId).setParameter("since", Timestamp.from(since)).getResultList();
            long consumed = 0, uses = 0;
            Map<String, Long> byCapability = new TreeMap<>(), usesByCapability = new TreeMap<>(), daily = new TreeMap<>();
            for (Object[] r : rows) {
                long amount = ((Number) r[2]).longValue(), count = ((Number) r[3]).longValue();
                consumed += amount; uses += count;
                byCapability.merge((String) r[1], amount, Long::sum);
                usesByCapability.merge((String) r[1], count, Long::sum);
                daily.merge((String) r[0], amount, Long::sum);
            }
            return new BillingDtos.TenantUsage(facts[0] == null ? 0 : ((Number) facts[0]).longValue(),
                    facts[1] == null ? null : instant(facts[1]), facts[2] == null ? null : instant(facts[2]),
                    facts[3] == null ? 0 : ((Number) facts[3]).longValue(), facts[4] == null ? null : instant(facts[4]),
                    consumed, uses, byCapability, usesByCapability, daily);
        });
    }

    private Object[] balance(Long tenantId) {
        return (Object[]) em.createNativeQuery("SELECT gift_available, purchased_available, held FROM billing_accounts WHERE tenant_id = :tenant")
                .setParameter("tenant", tenantId).getResultStream().findFirst().orElse(null);
    }

    /** 累计金额按类型和来源分组；来源从账本事件编号的前缀判断，CAPTURE 不区分来源。 */
    @SuppressWarnings("unchecked")
    private List<Object[]> lifetime(Long tenantId) {
        return em.createNativeQuery("""
                SELECT l.type,
                       CASE WHEN l.event_key LIKE 'redeem-code:%' THEN 'REDEEM_CODE'
                            WHEN l.event_key LIKE 'admin-credits:%' THEN 'ADMIN'
                            WHEN l.event_key LIKE 'admin-membership:%' THEN 'ADMIN_MEMBERSHIP'
                            WHEN l.event_key LIKE 'partner-credits:%' THEN 'PARTNER'
                            ELSE 'ORDER' END,
                       COALESCE(SUM(CASE WHEN l.type = 'CAPTURE' THEN -l.held_delta ELSE l.gift_delta + l.purchased_delta END), 0)
                FROM billing_ledger l
                WHERE l.tenant_id = :tenant AND l.type IN ('CAPTURE', 'PURCHASE', 'GRANT')
                GROUP BY 1, 2""")
                .setParameter("tenant", tenantId).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(Long tenantId, Instant since, String type, String capability) {
        return em.createNativeQuery(TaskUsageStore.REPORT + """
                SELECT l.id, l.actor_user_id, l.type, r.capability, l.description,
                       l.gift_delta + l.purchased_delta, l.held_delta, l.available_after, l.created_at,
                       COALESCE(u.model,CASE r.capability WHEN 'IMAGE' THEN '一方志Image'
                           WHEN 'VIDEO_ANALYSIS' THEN '一方志反推' WHEN 'VIDEO_PLAN' THEN '一方志提示词' WHEN 'IMAGE_INSPIRATION' THEN '一方志提示词' WHEN 'COPY' THEN '一方志文案' END),
                       u.elapsed_millis, u.status, u.preview_elapsed_millis
                FROM billing_ledger l
                LEFT JOIN billing_reservations r ON r.id = l.reservation_id AND r.tenant_id = l.tenant_id
                LEFT JOIN logs u ON u.reservation_id = l.reservation_id
                WHERE l.tenant_id = :tenant AND l.created_at >= :since
                  AND (:type = '' OR l.type = :type) AND (:capability = '' OR r.capability = :capability)
                ORDER BY l.id DESC LIMIT :limit""")
                .setParameter("tenant", tenantId).setParameter("since", Timestamp.from(since))
                .setParameter("type", blank(type) ? "" : type).setParameter("capability", blank(capability) ? "" : capability)
                .setParameter("limit", PER_TENANT_LIMIT).getResultList();
    }

    /** 每种类型、功能的金额：CAPTURE 取扣除的预占，其余取可用烟火的增加量。 */
    @SuppressWarnings("unchecked")
    private List<Object[]> totals(Long tenantId, Instant since, Instant today) {
        return em.createNativeQuery("""
                SELECT l.type, r.capability,
                       COALESCE(SUM(CASE WHEN l.type = 'CAPTURE' THEN -l.held_delta ELSE l.gift_delta + l.purchased_delta END), 0),
                       COALESCE(SUM(CASE WHEN l.type = 'CAPTURE' AND l.created_at >= :today THEN -l.held_delta ELSE 0 END), 0)
                FROM billing_ledger l
                LEFT JOIN billing_reservations r ON r.id = l.reservation_id AND r.tenant_id = l.tenant_id
                WHERE l.tenant_id = :tenant AND l.created_at >= :since AND l.type IN ('CAPTURE', 'RELEASE', 'PURCHASE', 'GRANT')
                GROUP BY l.type, r.capability""")
                .setParameter("tenant", tenantId).setParameter("since", Timestamp.from(since))
                .setParameter("today", Timestamp.from(today)).getResultList();
    }

    /** 按北京时间日期、类型、功能分组的金额，口径同 totals。 */
    @SuppressWarnings("unchecked")
    private List<Object[]> dailyTotals(Long tenantId, Instant since) {
        return em.createNativeQuery("""
                SELECT (l.created_at AT TIME ZONE 'Asia/Shanghai')::date, l.type, r.capability,
                       COALESCE(SUM(CASE WHEN l.type = 'CAPTURE' THEN -l.held_delta ELSE l.gift_delta + l.purchased_delta END), 0)
                FROM billing_ledger l
                LEFT JOIN billing_reservations r ON r.id = l.reservation_id AND r.tenant_id = l.tenant_id
                WHERE l.tenant_id = :tenant AND l.created_at >= :since AND l.type IN ('CAPTURE', 'RELEASE', 'PURCHASE', 'GRANT')
                GROUP BY 1, 2, 3""")
                .setParameter("tenant", tenantId).setParameter("since", Timestamp.from(since)).getResultList();
    }

    private <T> T inTenant(Long tenantId, Supplier<T> work) {
        return TenantContext.runAs(tenantId, () -> {
            var tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setReadOnly(true);
            return tx.execute(status -> work.get());
        });
    }

    /** days=1 表示今天（北京时间零点起），其余为最近 N 天。 */
    static Instant since(int days) {
        if (days < 1 || days > 90) throw BizException.of(ErrorCode.BAD_REQUEST, "时间范围为 1 至 90 天");
        return days == 1 ? LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant() : Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        throw new IllegalStateException("unexpected timestamp " + value);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void checkFilter(String value, Set<String> allowed) {
        if (!blank(value) && !allowed.contains(value)) throw BizException.of(ErrorCode.BAD_REQUEST, "筛选条件无效");
    }
}
