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
    static final Set<String> CAPABILITIES = Set.of("IMAGE", "VIDEO", "VIDEO_ANALYSIS", "VIDEO_PLAN", "LIVE");
    private static final int PER_TENANT_LIMIT = 2000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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
                            ((Number) r[7]).longValue(), instant(r[8])))
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

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(Long tenantId, Instant since, String type, String capability) {
        return em.createNativeQuery("""
                SELECT l.id, l.actor_user_id, l.type, r.capability, l.description,
                       l.gift_delta + l.purchased_delta, l.held_delta, l.available_after, l.created_at
                FROM billing_ledger l
                LEFT JOIN billing_reservations r ON r.id = l.reservation_id AND r.tenant_id = l.tenant_id
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
