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
    private final AllianceQuotaGrantRepository grants;
    private final AllianceVoucherRepository vouchers;
    private final AllianceSettlementRepository settlements;

    public List<AllianceDtos.PlatformOffer> pendingOffers() {
        List<AllianceDtos.PlatformOffer> result = new ArrayList<>();
        forEachTenant(tenant -> offerService.pendingReview().forEach(o -> result.add(new AllianceDtos.PlatformOffer(
                tenant, o.getSupplierName(), o.getSupplierCreditCode(), o.getStoreAddress(), AllianceDtos.OfferView.of(o, 0)))));
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

    public List<AllianceDtos.PlatformSettlement> settlements(String status) {
        String wanted = status == null || status.isBlank() ? "HOLDING" : status;
        if (!Set.of("HOLDING", "PAID", "VOID").contains(wanted)) throw BizException.of(ErrorCode.BAD_REQUEST, "status 只能是 HOLDING、PAID 或 VOID");
        Instant now = Instant.now();
        List<AllianceDtos.PlatformSettlement> result = new ArrayList<>();
        forEachTenant(tenant -> {
            String supplierName = tenants.name(tenant).orElse("联盟商户");
            settlementService.byStatus(wanted).forEach(s -> result.add(new AllianceDtos.PlatformSettlement(tenant, supplierName,
                    s.getHolderTenantId(), s.getVoucherRef(), AllianceDtos.SettlementView.of(s, now))));
        });
        result.sort(Comparator.comparing(p -> p.settlement().createdAt()));
        return result.stream().limit(LIMIT).toList();
    }

    /** 标记前再看一眼领券方：申诉刚提交、冻结还没同步过去时，也不能结算。 */
    public AllianceDtos.SettlementView markPaid(Long tenantId, Long settlementId, String reference, Long operatorId) {
        var settlement = TenantContext.runAs(tenantId, () -> settlementService.require(settlementId));
        var voucher = TenantContext.runAs(settlement.getHolderTenantId(), () -> voucherService.require(settlement.getVoucherRef()));
        if ("DISPUTED".equals(voucher.getStatus())) throw BizException.of(ErrorCode.CONFLICT, "领券商户正在申诉，处理完才能结算");
        return TenantContext.runAs(tenantId, () -> settlementService.markPaid(settlementId, reference, operatorId));
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
