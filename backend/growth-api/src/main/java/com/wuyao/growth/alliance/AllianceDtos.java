package com.wuyao.growth.alliance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AllianceDtos {
    private AllianceDtos() {}

    // ---------- 请求 ----------

    public record OfferRequest(@NotNull Long storeId, @NotNull Long productId, @Min(1) @Max(1000) int quantity) {}

    public record ReviewRequest(boolean approved, @Size(max = 300) String note) {}

    public record RedeemRequest(@NotBlank @Pattern(regexp = "\\d{8}", message = "请输入 8 位数字券码") String code) {}

    public record DisputeRequest(@NotBlank @Size(max = 300) String reason) {}

    public record DisputeResolution(boolean valid, @Size(max = 300) String note) {}

    public record PaidRequest(@NotBlank @Size(min = 4, max = 150) String reference) {}

    // ---------- 供券商户 ----------

    public record OfferView(Long id, Long storeId, String storeName, Long productId, String productName, String saleUnit,
                            long faceValueMinor, int settleRateBp, long settleAmountMinor, int totalQty, int claimedQty,
                            long redeemedQty, String status, String reviewNote, Instant createdAt) {
        static OfferView of(AllianceOffer offer, long redeemed) {
            return new OfferView(offer.getId(), offer.getStoreId(), offer.getStoreName(), offer.getProductId(),
                    offer.getProductName(), offer.getSaleUnit(), offer.getFaceValueMinor(), offer.getSettleRateBp(),
                    offer.getSettleAmountMinor(), offer.getTotalQty(), offer.getClaimedQty(), redeemed, offer.getStatus(),
                    offer.getReviewNote(), offer.getCreatedAt());
        }
    }

    public record RedemptionPreview(String productName, String saleUnit, String holderName, long faceValueMinor,
                                    Instant expiresAt) {}

    public record RedemptionView(String productName, String holderName, long faceValueMinor, Instant redeemedAt,
                                 Long redeemedBy) {}

    /** state：IN_DISPUTE_WINDOW 申诉期 / FROZEN 申诉处理中 / PAYABLE 待结算 / PAID 已结算 / VOID 已作废。 */
    public record SettlementView(Long id, String productName, long faceValueMinor, long settleAmountMinor, String state,
                                 Instant payableAfter, Instant paidAt, String paidReference, Instant createdAt) {
        static SettlementView of(AllianceSettlement s, Instant now) {
            return new SettlementView(s.getId(), s.getProductName(), s.getFaceValueMinor(), s.getSettleAmountMinor(),
                    state(s, now), s.getPayableAfter(), s.getPaidAt(), s.getPaidReference(), s.getCreatedAt());
        }

        static String state(AllianceSettlement s, Instant now) {
            if (!"HOLDING".equals(s.getStatus())) return s.getStatus();
            if (s.isFrozen()) return "FROZEN";
            return now.isBefore(s.getPayableAfter()) ? "IN_DISPUTE_WINDOW" : "PAYABLE";
        }
    }

    // ---------- 领券商户 ----------

    public record QuotaPeriod(String planName, long amountMinor, long usedMinor, long remainingMinor,
                              Instant validFrom, Instant validUntil) {
        static QuotaPeriod of(AllianceQuotaGrant g) {
            return new QuotaPeriod(g.getPlanName(), g.getAmountMinor(), g.getUsedMinor(), g.remainingMinor(),
                    g.getValidFrom(), g.getValidUntil());
        }
    }

    public record QuotaView(QuotaPeriod current, List<QuotaPeriod> upcoming, int perSupplierLimit) {}

    public record PoolItem(Long offerId, String productName, String productCategory, String saleUnit, Long coverAssetId,
                           String storeName, String storeAddress, String storePhone, String storeHours,
                           String supplierName, long faceValueMinor, int remaining) {
        static PoolItem of(AllianceOffer o) {
            return new PoolItem(o.getId(), o.getProductName(), o.getProductCategory(), o.getSaleUnit(), o.getCoverAssetId(),
                    o.getStoreName(), o.getStoreAddress(), o.getStorePhone(), o.getStoreHours(), o.getSupplierName(),
                    o.getFaceValueMinor(), o.remaining());
        }
    }

    /**
     * status：CLAIMING 领取中 / ACTIVE 可使用 / CLAIM_FAILED 领取失败 / REDEEMED 已核销 / DISPUTED 申诉中 / EXPIRED 已过期。
     * code 只在单张详情里返回。
     */
    public record VoucherView(UUID ref, String status, String productName, String saleUnit, Long coverAssetId,
                              long faceValueMinor, String storeName, String storeAddress, String storePhone,
                              String storeHours, Instant claimedAt, Instant expiresAt, Instant redeemedAt,
                              Instant disputeDeadline, boolean canDispute, String disputeReason, String disputeResult,
                              String disputeNote, String code) {
        static VoucherView of(AllianceVoucher v, Instant now, int disputeDays, boolean withCode) {
            String status = "ACTIVE".equals(v.getStatus()) && !now.isBefore(v.getExpiresAt()) ? "EXPIRED" : v.getStatus();
            Instant deadline = v.getRedeemedAt() == null ? null : v.getRedeemedAt().plus(java.time.Duration.ofDays(disputeDays));
            boolean canDispute = "REDEEMED".equals(v.getStatus()) && v.getDisputeResult() == null
                    && deadline != null && now.isBefore(deadline);
            return new VoucherView(v.getVoucherRef(), status, v.getProductName(), v.getSaleUnit(), v.getCoverAssetId(),
                    v.getFaceValueMinor(), v.getStoreName(), v.getStoreAddress(), v.getStorePhone(), v.getStoreHours(),
                    v.getClaimedAt(), v.getExpiresAt(), v.getRedeemedAt(), deadline, canDispute, v.getDisputeReason(),
                    v.getDisputeResult(), v.getDisputeNote(), withCode && "ACTIVE".equals(status) ? v.getCode() : null);
        }
    }

    // ---------- 平台运营 ----------

    public record PlatformOffer(Long tenantId, String supplierName, String supplierCreditCode, String storeAddress,
                                OfferView offer) {}

    public record PlatformDispute(Long holderTenantId, String holderName, Long supplierTenantId, UUID ref,
                                  String productName, String storeName, long faceValueMinor, Instant redeemedAt,
                                  String disputeReason, Instant disputedAt) {}

    public record PlatformSettlement(Long tenantId, String supplierName, Long holderTenantId, UUID ref,
                                     SettlementView settlement) {}

    public record Summary(long grantedMinor, long claimedFaceMinor, long redeemedFaceMinor, long expiredFaceMinor,
                          long holdingSettleMinor, long paidSettleMinor, long pendingOffers, long openDisputes) {}
}
