package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import com.wuyao.growth.billing.BillingLedgerReport;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 服务商后台的待办首页和经营看板。负责人看全部（可按 BD 筛），BD 只看自己名下的商户和自己生成的激活码。
 * 商户的会员、余额和使用情况要在每个商户的租户里各读一次，商户很多时会慢，届时改成每天汇总。
 */
@Service
@RequiredArgsConstructor
public class PartnerInsights {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final Duration IDLE = Duration.ofDays(7);
    static final Duration EXPIRING = Duration.ofDays(7);
    static final Duration RECENTLY_EXPIRED = Duration.ofDays(30);
    static final List<String> TODO_TYPES = List.of("EXPIRING", "EXPIRED", "LOW_BALANCE", "NOT_STARTED", "IDLE");

    private final PartnerService partners;
    private final PartnerStore store;
    private final BillingLedgerReport report;

    /** 待办：即将到期、刚过期、余额不足、开通了没用过、7 天没用。每项带上商户，前端点开就是商户详情。 */
    public PartnerDtos.Workbench workbench(Long userId) {
        var access = partners.requirePartner(userId);
        Instant now = Instant.now();
        var rows = rows(partners.visible(access), now);
        List<PartnerDtos.Todo> todos = new ArrayList<>();
        for (var r : rows) {
            var t = r.view();
            var u = r.usage();
            if (Boolean.TRUE.equals(t.memberActive()) && t.memberUntil().isBefore(now.plus(EXPIRING))) todos.add(todo("EXPIRING", r));
            if (Boolean.FALSE.equals(t.memberActive()) && t.memberUntil() != null && t.memberUntil().isAfter(now.minus(RECENTLY_EXPIRED)))
                todos.add(todo("EXPIRED", r));
            if (PartnerService.lowBalance(u.available(), u.lastTopUpCredits())) todos.add(todo("LOW_BALANCE", r));
            if (t.memberUntil() != null && u.firstUsedAt() == null) todos.add(todo("NOT_STARTED", r));
            if (Boolean.TRUE.equals(t.memberActive()) && u.lastUsedAt() != null && u.lastUsedAt().isBefore(now.minus(IDLE))) todos.add(todo("IDLE", r));
        }
        todos.sort(Comparator.comparingInt((PartnerDtos.Todo d) -> TODO_TYPES.indexOf(d.type()))
                .thenComparing(d -> d.tenant().memberUntil(), Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, Long> counts = new LinkedHashMap<>();
        TODO_TYPES.forEach(type -> counts.put(type, todos.stream().filter(d -> d.type().equals(type)).count()));
        return new PartnerDtos.Workbench(todos, counts, now);
    }

    /** 经营看板：最近 days 天（1 是今天）的商户、消耗、烟火和激活码数字，按天的趋势，商户和 BD 排行。 */
    public PartnerDtos.Dashboard dashboard(Long userId, int days, Long bdUserId) {
        if (!Set.of(1, 7, 30, 90).contains(days)) throw BizException.of(ErrorCode.BAD_REQUEST, "时间范围只能是今天、7 天、30 天或 90 天");
        var access = partners.requirePartner(userId);
        boolean owner = access.owner();
        Long partnerId = access.partner().id();
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZONE), first = today.minusDays(days - 1L);
        Instant since = days == 1 ? today.atStartOfDay(ZONE).toInstant() : now.minus(Duration.ofDays(days));

        var bindings = partners.visible(access);
        if (owner && bdUserId != null)
            bindings = bindings.stream().filter(b -> bdUserId == 0 ? b.bdUserId() == null : bdUserId.equals(b.bdUserId())).toList();
        var rows = rows(bindings, since);
        Set<Long> tenantIds = bindings.stream().map(PartnerStore.Binding::tenantId).collect(Collectors.toSet());

        Map<String, long[]> daily = new LinkedHashMap<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) daily.put(d.toString(), new long[4]);

        long consumed = 0, uses = 0, activeMembers = 0, expiring = 0, expired = 0, never = 0, active = 0, firstUses = 0, newTenants = 0;
        Map<String, Long> byCapability = new TreeMap<>();
        for (var r : rows) {
            var t = r.view();
            var u = r.usage();
            if (Boolean.TRUE.equals(t.memberActive())) {
                activeMembers++;
                if (t.memberUntil().isBefore(now.plus(EXPIRING))) expiring++;
            } else if (Boolean.FALSE.equals(t.memberActive())) {
                if (t.memberUntil() == null) never++; else expired++;
            }
            if (u.lastUsedAt() != null && !u.lastUsedAt().isBefore(since)) active++;
            if (u.firstUsedAt() != null && !u.firstUsedAt().isBefore(since)) firstUses++;
            if (!t.boundAt().isBefore(since)) newTenants++;
            consumed += u.consumed();
            uses += u.uses();
            u.consumedByCapability().forEach((k, v) -> byCapability.merge(k, v, Long::sum));
            u.dailyConsumed().forEach((date, v) -> { var p = daily.get(date); if (p != null) p[0] += v; });
            var boundDay = daily.get(LocalDate.ofInstant(t.boundAt(), ZONE).toString());
            if (boundDay != null) boundDay[2]++;
        }

        Long granted = null, transferred = null;
        if (owner) {
            long g = 0, x = 0;
            for (var c : store.creditDays(partnerId, since)) {
                if ("GRANT".equals(c.type())) { g += c.delta(); continue; }
                if (!tenantIds.contains(c.tenantId())) continue;
                x += -c.delta();
                var p = daily.get(c.date());
                if (p != null) p[1] += -c.delta();
            }
            granted = bdUserId == null ? g : null;
            transferred = x;
        }

        // 激活码按生成人认：BD 只算自己的；负责人按 BD 筛时只算那个 BD 的，「我直接管的」算负责人账号生成的。
        Set<Long> creators = !owner ? Set.of(userId) : bdUserId == null ? null : bdUserId == 0
                ? store.members(partnerId).stream().filter(m -> "OWNER".equals(m.role())).map(PartnerStore.MemberRow::userId).collect(Collectors.toSet())
                : Set.of(bdUserId);
        long generated = 0, redeemed = 0, renewals = 0, voided = 0;
        Map<Long, long[]> byCreator = new HashMap<>();
        for (var c : store.codeEvents(partnerId, since)) {
            if (creators != null && !creators.contains(c.createdBy())) continue;
            var stat = byCreator.computeIfAbsent(c.createdBy(), k -> new long[2]);
            if (!c.createdAt().isBefore(since)) { generated++; if (c.renewal()) { renewals++; stat[1]++; } }
            if (c.redeemedAt() != null) {
                if (!c.redeemedAt().isBefore(since)) { redeemed++; stat[0]++; }
                var p = daily.get(LocalDate.ofInstant(c.redeemedAt(), ZONE).toString());
                if (p != null) p[3]++;
            }
            if (c.voidedAt() != null && !c.voidedAt().isBefore(since)) voided++;
        }

        var points = daily.entrySet().stream().map(e -> new PartnerDtos.DailyPoint(e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[2], e.getValue()[3])).toList();
        var top = rows.stream().filter(r -> r.usage().consumed() > 0)
                .sorted(Comparator.comparingLong((Row r) -> r.usage().consumed()).reversed())
                .limit(10).map(r -> new PartnerDtos.TenantRank(r.view().tenantId(), r.view().tenantName(), r.view().bdName(),
                        r.usage().consumed(), r.usage().uses(), r.usage().lastUsedAt())).toList();
        List<PartnerDtos.BdRank> bds = owner && bdUserId == null ? bdRanks(partnerId, rows, byCreator, since) : List.of();
        return new PartnerDtos.Dashboard(days, owner ? access.partner().creditsAvailable() : null, rows.size(), newTenants, activeMembers,
                expiring, expired, never, active, firstUses, consumed, uses, byCapability, granted, transferred, generated, redeemed, renewals, voided,
                points, top, bds);
    }

    private List<PartnerDtos.BdRank> bdRanks(Long partnerId, List<Row> rows, Map<Long, long[]> byCreator, Instant since) {
        List<PartnerDtos.BdRank> ranks = new ArrayList<>();
        for (var m : partners.members(partnerId)) {
            if (!"BD".equals(m.role())) continue;
            var mine = rows.stream().filter(r -> m.userId().equals(r.view().bdUserId())).toList();
            var codes = byCreator.getOrDefault(m.userId(), new long[2]);
            ranks.add(new PartnerDtos.BdRank(m.userId(), m.name(), mine.size(), mine.stream().mapToLong(r -> r.usage().consumed()).sum(),
                    mine.stream().filter(r -> r.usage().lastUsedAt() != null && !r.usage().lastUsedAt().isBefore(since)).count(),
                    codes[0], codes[1]));
        }
        ranks.sort(Comparator.comparingLong(PartnerDtos.BdRank::consumed).reversed());
        return ranks;
    }

    record Row(PartnerDtos.TenantView view, BillingDtos.TenantUsage usage) {}

    private List<Row> rows(List<PartnerStore.Binding> bindings, Instant since) {
        Map<Long, String> names = partners.tenantNames();
        Map<Long, PlatformAccountDirectory.PlatformUser> owners = partners.owners();
        Map<Long, String> bdNames = new HashMap<>();
        return bindings.stream().map(b -> new Row(partners.view(b, names, owners, bdNames), report.usage(b.tenantId(), since))).toList();
    }

    private static PartnerDtos.Todo todo(String type, Row r) {
        return new PartnerDtos.Todo(type, r.view(), r.usage().lastUsedAt(), r.usage().lastTopUpCredits());
    }
}
