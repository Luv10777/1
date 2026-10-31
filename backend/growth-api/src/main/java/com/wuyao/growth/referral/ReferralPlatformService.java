package com.wuyao.growth.referral;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.community.BankCardDtos;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 平台运营看邀请关系、返利明细、推广人排行和提现单，并处理作废返利、审核提现。数据按商户隔离，这里像烟火日志一样逐个商户只读取一遍再汇总；
 * 调用方必须先核对平台运营身份。每个商户最多取最近 PER_TENANT_LIMIT 条返利。
 */
@Service
@RequiredArgsConstructor
public class ReferralPlatformService {
    static final int PER_TENANT_LIMIT = 2000;
    private static final Set<String> SCOPES = Set.of("ALL", "INVITED", "COMPANY");
    private static final Set<String> STATES = Set.of("FROZEN", "AVAILABLE", "WITHDRAWING", "WITHDRAWN", "VOIDED");
    private static final Set<String> WITHDRAWAL_STATUSES = Set.of("PENDING", "REJECTED", "PAID");

    private final PlatformAccountDirectory directory;
    private final ReferralCodeRepository codes;
    private final ReferralAttributionRepository attributions;
    private final ReferralCommissionRepository commissions;
    private final ReferralWithdrawalRepository withdrawals;
    private final ReferralWithdrawalService withdrawalService;
    private final PlatformTransactionManager transactionManager;

    public PageResult<ReferralDtos.AdminRelation> relations(String keyword, String scope, int page, int size) {
        String filter = StringUtils.hasText(scope) ? scope : "ALL";
        if (!SCOPES.contains(filter)) throw BizException.of(ErrorCode.BAD_REQUEST, "筛选条件不正确");
        var data = snapshot();
        var rows = new ArrayList<ReferralDtos.AdminRelation>();
        for (var tenant : data.tenants().entrySet()) {
            var owner = data.ownerOf(tenant.getKey());
            var attribution = data.attributions().get(tenant.getKey());
            var referrer = attribution == null ? null : data.users().get(attribution.getReferrerUserId());
            var mine = data.commissions().stream().filter(c -> c.getInviteeTenantId().equals(tenant.getKey())).toList();
            rows.add(new ReferralDtos.AdminRelation(tenant.getKey(), tenant.getValue(),
                    owner == null ? null : owner.name(), owner == null ? "—" : ReferralService.maskPhone(owner.phone()),
                    owner == null ? null : owner.createdAt(),
                    attribution == null ? null : attribution.getReferrerUserId(),
                    referrer == null ? null : referrer.name(), referrer == null ? null : ReferralService.maskPhone(referrer.phone()),
                    attribution == null ? null : data.tenants().get(attribution.getReferrerTenantId()),
                    attribution == null ? null : attribution.getCode(), attribution == null ? null : attribution.getCreatedAt(),
                    paid(mine, ReferralService.TOPUP), paid(mine, ReferralService.MEMBERSHIP),
                    mine.stream().filter(c -> !"VOIDED".equals(c.getStatus())).mapToLong(ReferralCommission::getAmountMinor).sum()));
        }
        Predicate<ReferralDtos.AdminRelation> inScope = switch (filter) {
            case "INVITED" -> r -> r.referrerUserId() != null;
            case "COMPANY" -> r -> r.referrerUserId() == null;
            default -> r -> true;
        };
        String needle = StringUtils.hasText(keyword) ? keyword.trim() : null;
        var matched = rows.stream().filter(inScope)
                .filter(r -> needle == null || matches(needle, r.tenantName(), r.ownerName(), r.referrerName(), r.referrerTenantName(), r.code())
                        || phoneMatches(needle, data, r.tenantId(), r.referrerUserId()))
                .sorted(Comparator.comparing(ReferralDtos.AdminRelation::registeredAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ReferralDtos.AdminRelation::tenantId, Comparator.reverseOrder()))
                .toList();
        return page(matched, page, size);
    }

    public ReferralDtos.AdminCommissionPage commissions(Long referrerUserId, String state, int page, int size) {
        if (StringUtils.hasText(state) && !STATES.contains(state)) throw BizException.of(ErrorCode.BAD_REQUEST, "状态不正确");
        var data = snapshot();
        Instant now = Instant.now();
        var matched = data.commissions().stream()
                .filter(c -> referrerUserId == null || referrerUserId.equals(c.getReferrerUserId()))
                .filter(c -> !StringUtils.hasText(state) || state.equals(ReferralService.state(c, now)))
                .sorted(Comparator.comparing(ReferralCommission::getCreatedAt).reversed().thenComparing(ReferralCommission::getId, Comparator.reverseOrder()))
                .toList();
        long frozen = matched.stream().filter(c -> "FROZEN".equals(ReferralService.state(c, now))).mapToLong(ReferralCommission::getAmountMinor).sum();
        long available = matched.stream().filter(c -> "AVAILABLE".equals(ReferralService.state(c, now))).mapToLong(ReferralCommission::getAmountMinor).sum();
        var views = matched.stream().map(c -> {
            var referrer = data.users().get(c.getReferrerUserId());
            var invitee = data.ownerOf(c.getInviteeTenantId());
            return new ReferralDtos.AdminCommission(c.getId(), c.getTenantId(), c.getReferrerUserId(), referrer == null ? null : referrer.name(),
                    referrer == null ? "—" : ReferralService.maskPhone(referrer.phone()), c.getInviteeTenantId(),
                    data.tenants().get(c.getInviteeTenantId()), invitee == null ? "—" : ReferralService.maskPhone(invitee.phone()),
                    c.getOrderKind(), c.getPaidMinor(), c.getRateBp(), c.getAmountMinor(), ReferralService.state(c, now),
                    c.getVoidNote(), c.getAvailableAt(), c.getCreatedAt());
        }).toList();
        return new ReferralDtos.AdminCommissionPage(page(views, page, size), frozen, available);
    }

    public List<ReferralDtos.AdminPromoter> promoters() {
        var data = snapshot();
        Instant now = Instant.now();
        var byReferrer = data.commissions().stream().collect(Collectors.groupingBy(ReferralCommission::getReferrerUserId));
        var inviteeCounts = data.attributions().values().stream()
                .collect(Collectors.groupingBy(ReferralAttribution::getReferrerUserId, Collectors.counting()));
        return data.codes().stream().map(code -> {
            var user = data.users().get(code.getUserId());
            var mine = byReferrer.getOrDefault(code.getUserId(), List.of());
            return new ReferralDtos.AdminPromoter(code.getUserId(), user == null ? null : user.name(),
                    user == null ? "—" : ReferralService.maskPhone(user.phone()), data.tenants().get(code.getTenantId()), code.getCode(),
                    inviteeCounts.getOrDefault(code.getUserId(), 0L),
                    mine.stream().map(ReferralCommission::getInviteeTenantId).distinct().count(),
                    sum(mine, c -> !"VOIDED".equals(c.getStatus())),
                    sum(mine, c -> "FROZEN".equals(ReferralService.state(c, now))),
                    sum(mine, c -> "AVAILABLE".equals(ReferralService.state(c, now))),
                    code.getCreatedAt());
        }).sorted(Comparator.comparingLong(ReferralDtos.AdminPromoter::commissionMinor).reversed()
                .thenComparing(Comparator.comparingLong(ReferralDtos.AdminPromoter::inviteeCount).reversed())
                .thenComparing(ReferralDtos.AdminPromoter::codeCreatedAt)).toList();
    }

    /** 提现单按申请时间倒序；待审核的合计不受筛选影响。 */
    public ReferralDtos.AdminWithdrawalPage withdrawals(String status, int page, int size) {
        if (StringUtils.hasText(status) && !WITHDRAWAL_STATUSES.contains(status)) throw BizException.of(ErrorCode.BAD_REQUEST, "状态不正确");
        var tenants = new java.util.LinkedHashMap<Long, String>();
        directory.scopes().forEach(s -> tenants.put(s.id(), s.name()));
        var users = directory.users().stream().collect(Collectors.toMap(PlatformAccountDirectory.PlatformUser::id, Function.identity()));
        var rows = new ArrayList<ReferralWithdrawal>();
        for (Long tenantId : tenants.keySet()) {
            inTenant(tenantId, () -> rows.addAll(withdrawals.findTop2000ByTenantIdOrderByCreatedAtDesc(tenantId)));
        }
        var pending = rows.stream().filter(w -> "PENDING".equals(w.getStatus())).toList();
        var views = rows.stream().filter(w -> !StringUtils.hasText(status) || status.equals(w.getStatus()))
                .sorted(Comparator.comparing(ReferralWithdrawal::getCreatedAt).reversed().thenComparing(ReferralWithdrawal::getId, Comparator.reverseOrder()))
                .map(w -> {
                    var user = users.get(w.getUserId());
                    return new ReferralDtos.AdminWithdrawal(w.getId(), w.getTenantId(), tenants.get(w.getTenantId()), w.getUserId(),
                            user == null ? null : user.name(), user == null ? "—" : ReferralService.maskPhone(user.phone()),
                            w.getCommissionMinor(), w.getClawbackMinor(), w.getAmountMinor(), w.getStatus(), w.getBankName(),
                            w.getCardLast4(), w.getReviewNote(), w.getTaxMinor(), w.getNetMinor(), w.getPaidReference(),
                            w.getPaidDate(), w.getCreatedAt(), w.getReviewedAt());
                }).toList();
        return new ReferralDtos.AdminWithdrawalPage(page(views, page, size), pending.size(),
                pending.stream().mapToLong(ReferralWithdrawal::getAmountMinor).sum());
    }

    public BankCardDtos.PayeeDetail revealPayee(Long operatorId, Long tenantId, Long id, String purpose) {
        return TenantContext.runAs(tenantId, () -> withdrawalService.reveal(id, purpose, operatorId));
    }

    public ReferralDtos.Withdrawal reject(Long operatorId, Long tenantId, Long id, String note) {
        return TenantContext.runAs(tenantId, () -> withdrawalService.reject(id, note, operatorId));
    }

    public ReferralDtos.Withdrawal markPaid(Long operatorId, Long tenantId, Long id, ReferralDtos.PayWithdrawal request) {
        return TenantContext.runAs(tenantId, () -> withdrawalService.markPaid(id, request, operatorId));
    }

    public void voidCommission(Long operatorId, Long tenantId, Long id, String note) {
        TenantContext.runAs(tenantId, () -> {
            withdrawalService.voidCommission(id, note, operatorId);
            return null;
        });
    }

    private Snapshot snapshot() {
        var tenants = new java.util.LinkedHashMap<Long, String>();
        directory.scopes().forEach(s -> tenants.put(s.id(), s.name()));
        var users = directory.users().stream().collect(Collectors.toMap(PlatformAccountDirectory.PlatformUser::id, Function.identity()));
        var attributionMap = new HashMap<Long, ReferralAttribution>();
        var codeList = new ArrayList<ReferralCode>();
        var commissionList = new ArrayList<ReferralCommission>();
        for (Long tenantId : tenants.keySet()) {
            inTenant(tenantId, () -> {
                attributions.findById(tenantId).ifPresent(a -> attributionMap.put(tenantId, a));
                codeList.addAll(codes.findAll());
                commissionList.addAll(commissions.findTop2000ByTenantIdOrderByCreatedAtDesc(tenantId));
                return null;
            });
        }
        return new Snapshot(tenants, users, attributionMap, codeList, commissionList);
    }

    private <T> T inTenant(Long tenantId, Supplier<T> work) {
        return TenantContext.runAs(tenantId, () -> {
            var tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setReadOnly(true);
            return tx.execute(status -> work.get());
        });
    }

    private record Snapshot(Map<Long, String> tenants, Map<Long, PlatformAccountDirectory.PlatformUser> users,
                            Map<Long, ReferralAttribution> attributions, List<ReferralCode> codes, List<ReferralCommission> commissions) {
        /** 商户的注册人：自己注册的人就是这个商户的管理员，取最早的一个。 */
        PlatformAccountDirectory.PlatformUser ownerOf(Long tenantId) {
            return users.values().stream().filter(u -> tenantId.equals(u.tenantId()) && "OWNER".equals(u.role()))
                    .min(Comparator.comparing(PlatformAccountDirectory.PlatformUser::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
        }
    }

    private static boolean matches(String needle, String... values) {
        for (String value : values) if (value != null && value.contains(needle)) return true;
        return false;
    }

    /** 运营可以用完整手机号或后四位搜索，页面上只显示打码后的号码。 */
    private static boolean phoneMatches(String needle, Snapshot data, Long tenantId, Long referrerUserId) {
        if (!needle.matches("\\d{4,11}")) return false;
        var owner = data.ownerOf(tenantId);
        var referrer = referrerUserId == null ? null : data.users().get(referrerUserId);
        return Optional.ofNullable(owner).map(o -> o.phone() != null && o.phone().contains(needle)).orElse(false)
                || Optional.ofNullable(referrer).map(r -> r.phone() != null && r.phone().contains(needle)).orElse(false);
    }

    private static long paid(List<ReferralCommission> rows, String kind) {
        return rows.stream().filter(c -> kind.equals(c.getOrderKind()) && !"VOIDED".equals(c.getStatus())).mapToLong(ReferralCommission::getPaidMinor).sum();
    }

    private static long sum(List<ReferralCommission> rows, Predicate<ReferralCommission> filter) {
        return rows.stream().filter(filter).mapToLong(ReferralCommission::getAmountMinor).sum();
    }

    private static <T> PageResult<T> page(List<T> rows, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        int totalPages = (rows.size() + safeSize - 1) / safeSize;
        return new PageResult<>(rows.subList(from, to), safePage, safeSize, rows.size(), totalPages);
    }
}
