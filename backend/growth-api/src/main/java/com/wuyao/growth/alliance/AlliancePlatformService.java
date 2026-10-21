package com.wuyao.growth.alliance;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.TenantDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 平台运营的联盟券操作。数据分散在各商户的租户里，逐个商户切进去读写，不加 @Transactional。
 * 一期按商户逐个扫描，商户很多时需要改成分页或汇总表。调用方已校验运营身份。
 */
@Service
@RequiredArgsConstructor
public class AlliancePlatformService {
    private static final int LIMIT = 200;
    private final TenantDirectory tenants;
    private final AllianceOfferService offerService;
    private final AllianceVoucherService voucherService;
    private final AllianceSettlementService settlementService;
    private final AllianceAccountService accountService;
    private final AllianceStatementService statementService;
    private final AllianceQuotaGrantRepository grants;
    private final AllianceVoucherRepository vouchers;
    private final AllianceSettlementRepository settlements;

    public List<AllianceDtos.PlatformOffer> pendingOffers() {
        List<AllianceDtos.PlatformOffer> result = new ArrayList<>();
        forEachTenant(tenant -> {
            var pending = offerService.pendingReview();
            if (pending.isEmpty()) return;
            String accountStatus = accountService.status().orElse(null);
            pending.forEach(o -> result.add(new AllianceDtos.PlatformOffer(tenant, o.getSupplierName(), o.getSupplierCreditCode(),
                    o.getStoreAddress(), accountStatus, AllianceDtos.OfferView.of(o, 0))));
        });
        result.sort(Comparator.comparing(p -> p.offer().createdAt()));
        return result.stream().limit(LIMIT).toList();
    }

    public AllianceDtos.OfferView review(Long tenantId, Long offerId, AllianceDtos.ReviewRequest request, Long operatorId) {
        return TenantContext.runAs(tenantId, () -> offerService.review(offerId, request.approved(), request.note(),
                request.settleRateBp(), operatorId));
    }

    public List<AllianceDtos.PlatformDispute> disputes() {
        List<AllianceDtos.PlatformDispute> result = new ArrayList<>();
        forEachTenant(tenant -> {
            String holderName = tenants.name(tenant).orElse("联盟商户");
            voucherService.openDisputes().forEach(v -> result.add(new AllianceDtos.PlatformDispute(tenant, holderName,
                    v.getSupplierTenantId(), v.getVoucherRef(), v.getProductName(), v.getStoreName(), v.getFaceValueMinor(),
                    v.getRedeemedAt(), v.getDisputeReason(), v.getDisputedAt())));
        });
        result.sort(Comparator.comparing(AllianceDtos.PlatformDispute::disputedAt));
        return result.stream().limit(LIMIT).toList();
    }

    /** 先改供券方（结算单、领取记录），再改领券方（券）。两步都可以重复执行，中途失败可以再点一次。 */
    public void resolveDispute(Long holderTenantId, UUID ref, AllianceDtos.DisputeResolution resolution) {
        var voucher = TenantContext.runAs(holderTenantId, () -> voucherService.require(ref));
        if (!"DISPUTED".equals(voucher.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "这张券没有待处理的申诉");
        TenantContext.runAs(voucher.getSupplierTenantId(), () -> {
            settlementService.resolveDispute(ref, resolution.valid());
            return null;
        });
        TenantContext.runAs(holderTenantId, () -> {
            voucherService.resolveDispute(ref, resolution.valid(), resolution.note());
            return null;
        });
    }

    // ---------- 结算账户 ----------

    /** 结算账户列表；status 为空时只列待核对的。 */
    public List<AllianceDtos.PlatformAccount> accounts(String status) {
        String wanted = status == null || status.isBlank() ? "PENDING_VERIFY" : status;
        if (!Set.of("PENDING_VERIFY", "VERIFIED", "REJECTED").contains(wanted)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "status 只能是 PENDING_VERIFY、VERIFIED 或 REJECTED");
        }
        List<AllianceDtos.PlatformAccount> result = new ArrayList<>();
        forEachTenant(tenant -> accountService.forReview().filter(r -> wanted.equals(r.account().status()))
                .ifPresent(r -> result.add(new AllianceDtos.PlatformAccount(tenant, tenants.name(tenant).orElse("联盟商户"),
                        r.registeredCompanyName(), r.account()))));
        result.sort(Comparator.comparing(p -> p.account().updatedAt()));
        return result.stream().limit(LIMIT).toList();
    }

    public AllianceDtos.AccountView reviewAccount(Long tenantId, AllianceDtos.AccountReviewRequest request, Long operatorId) {
        return TenantContext.runAs(tenantId, () -> accountService.review(request.approved(), request.note(), operatorId));
    }

    public AllianceDtos.RevealView revealAccount(Long tenantId, String purpose, Long operatorId) {
        return TenantContext.runAs(tenantId, () -> accountService.reveal(purpose, operatorId));
    }

    // ---------- 对账单 ----------

    /** 对账单列表，可按状态和月份筛。 */
    public List<AllianceDtos.PlatformStatement> statements(String status, String period) {
        if (status != null && !status.isBlank() && !Set.of("PENDING_CONFIRM", "OBJECTED", "CONFIRMED", "PAID", "VOID").contains(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "对账单状态不正确");
        }
        List<AllianceDtos.PlatformStatement> result = new ArrayList<>();
        forEachTenant(tenant -> {
            var list = statementService.all().stream()
                    .filter(s -> status == null || status.isBlank() || status.equals(s.getStatus()))
                    .filter(s -> period == null || period.isBlank() || period.equals(s.getPeriod()))
                    .toList();
            if (list.isEmpty()) return;
            String name = tenants.name(tenant).orElse("联盟商户");
            String accountStatus = accountService.status().orElse(null);
            list.forEach(s -> result.add(new AllianceDtos.PlatformStatement(tenant, name, accountStatus, AllianceDtos.StatementView.of(s))));
        });
        result.sort(Comparator.comparing((AllianceDtos.PlatformStatement p) -> p.statement().period()).reversed()
                .thenComparing(p -> p.statement().id()));
        return result.stream().limit(LIMIT).toList();
    }

    public AllianceDtos.StatementDetail statement(Long tenantId, Long id) {
        return TenantContext.runAs(tenantId, () -> statementService.detail(id));
    }

    public AllianceDtos.StatementView statementPaid(Long tenantId, Long id, AllianceDtos.StatementPaidRequest request, Long operatorId) {
        return TenantContext.runAs(tenantId, () -> statementService.markPaid(id, request.reference(), request.paidDate(), operatorId));
    }

    public AllianceDtos.StatementView voidStatement(Long tenantId, Long id, String note, Long operatorId) {
        return TenantContext.runAs(tenantId, () -> statementService.voidStatement(id, note, operatorId));
    }

    public AllianceDtos.StatementView resolveStatement(Long tenantId, Long id, String note, Long operatorId) {
        return TenantContext.runAs(tenantId, () -> statementService.resolve(id, note, operatorId));
    }

    public AllianceDtos.RevealView revealStatementPayee(Long tenantId, Long id, String purpose, Long operatorId) {
        return TenantContext.runAs(tenantId, () -> statementService.revealPayee(id, purpose, operatorId));
    }

    /** 手动补生成某个月的对账单，返回这次新生成的张数。已有的跳过。 */
    public int generateStatements(java.time.YearMonth period) {
        int[] created = {0};
        forEachTenant(tenant -> {
            try {
                if (statementService.generate(period).isPresent()) created[0]++;
            } catch (org.springframework.dao.DataIntegrityViolationException concurrent) {
                // 定时任务同时在生成，这个商户这个月已经有了
            }
        });
        return created[0];
    }

    public AllianceDtos.Summary summary() {
        long[] totals = new long[8];
        Set<String> claimed = Set.of("ACTIVE", "REDEEMED", "DISPUTED", "EXPIRED");
        Set<String> redeemed = Set.of("REDEEMED", "DISPUTED");
        forEachTenant(tenant -> {
            totals[0] += grants.totalGranted(tenant);
            totals[1] += vouchers.sumFace(tenant, claimed);
            totals[2] += vouchers.sumFace(tenant, redeemed);
            totals[3] += vouchers.sumFace(tenant, Set.of("EXPIRED"));
            totals[4] += settlements.sumAmount(tenant, "HOLDING");
            totals[5] += settlements.sumAmount(tenant, "PAID");
            totals[6] += offerService.pendingReview().size();
            totals[7] += vouchers.countByTenantIdAndStatus(tenant, "DISPUTED");
        });
        return new AllianceDtos.Summary(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5], totals[6], totals[7]);
    }

    private void forEachTenant(Consumer<Long> action) {
        Long after = 0L;
        while (true) {
            var ids = tenants.idsAfter(after);
            if (ids.isEmpty()) return;
            for (Long tenant : ids) {
                TenantContext.runAs(tenant, () -> {
                    action.accept(tenant);
                    return null;
                });
            }
            after = ids.getLast();
        }
    }
}
