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

    /** 通过时必须填写与供券商户约定的结算比例（万分比，5000 即面值的 5 折）。 */
    public record ReviewRequest(boolean approved, @Size(max = 300) String note, @Min(1) @Max(10000) Integer settleRateBp) {}

    public record RedeemRequest(@NotBlank @Pattern(regexp = "\\d{8}", message = "请输入 8 位数字券码") String code) {}

    public record DisputeRequest(@NotBlank @Size(max = 300) String reason) {}

    public record DisputeResolution(boolean valid, @Size(max = 300) String note) {}

    public record PaidRequest(@NotBlank @Size(min = 4, max = 150) String reference) {}

    /** 当前生效的业务规则，页面上的说明文字从这里取，不写死。 */
    public record Rules(int perSupplierLimit, long monthlyFaceCapMinor, int inventorySharePercent,
                        int unlimitedInventoryCap, int voucherValidDays, int disputeDays, int redeemAttemptLimit,
                        int redeemLockMinutes, int statementConfirmDays) {
        static Rules of(AllianceProperties c) {
            return new Rules(c.getPerSupplierLimit(), c.getMonthlyFaceCapMinor(),
                    c.getInventorySharePercent(), c.getUnlimitedInventoryCap(), c.getVoucherValidDays(), c.getDisputeDays(),
                    c.getRedeemAttemptLimit(), c.getRedeemLockMinutes(), c.getStatementConfirmDays());
        }
    }

    // ---------- 结算账户与对账单 ----------

    /** 新填时必须带完整账号；修改时账号留空表示沿用原来的。 */
    public record AccountRequest(
            @NotBlank @Pattern(regexp = "CORPORATE|INDIVIDUAL_OWNER", message = "账户类型只能是对公账户或经营者本人账户") String accountType,
            @NotBlank @Size(max = 120) String accountName,
            @NotBlank @Size(max = 80) String bankName,
            @NotBlank @Size(max = 120) String branchName,
            @Pattern(regexp = "[0-9 ]{8,40}", message = "银行账号只能是数字") String accountNumber,
            @NotBlank @Size(max = 40) String financeContactName,
            @NotBlank @Pattern(regexp = "[0-9+()\\- ]{6,20}", message = "请填写有效的联系电话") String financeContactPhone) {}

    /** status：PENDING_VERIFY 待核对 / VERIFIED 已核对 / REJECTED 已驳回。账号只给后 4 位。 */
    public record AccountView(Long id, String accountType, String accountName, String bankName, String branchName,
                              String accountNumberLast4, String financeContactName, String financeContactPhone,
                              String status, String reviewNote, Instant reviewedAt, Instant updatedAt) {
        static AccountView of(AllianceSettlementAccount a) {
            return new AccountView(a.getId(), a.getAccountType(), a.getAccountName(), a.getBankName(), a.getBranchName(),
                    a.getAccountNumberLast4(), a.getFinanceContactName(), a.getFinanceContactPhone(), a.getStatus(),
                    a.getReviewNote(), a.getReviewedAt(), a.getUpdatedAt());
        }
    }

    public record AccountReviewRequest(boolean approved, @Size(max = 300) String note) {}

    public record RevealRequest(@NotBlank @Size(min = 4, max = 200) String purpose) {}

    public record RevealView(String accountName, String bankName, String branchName, String accountNumber) {}

    public record ObjectionRequest(@NotBlank @Size(max = 300) String reason) {}

    public record StatementNoteRequest(@NotBlank @Size(max = 300) String note) {}

    public record StatementPaidRequest(@NotBlank @Size(min = 4, max = 150) String reference, @NotNull java.time.LocalDate paidDate) {}

    /** status：PENDING_CONFIRM 待确认 / OBJECTED 有异议 / CONFIRMED 已确认待打款 / PAID 已打款 / VOID 已作废。 */
    public record StatementView(Long id, String period, int itemCount, long faceTotalMinor, long settleTotalMinor,
                                String status, Instant confirmDueAt, Instant confirmedAt, boolean autoConfirmed,
                                String objectionNote, String platformNote, String payeeAccountType, String payeeAccountName,
                                String payeeBankName, String payeeBranchName, String payeeAccountLast4,
                                String paidReference, java.time.LocalDate paidDate, Instant createdAt) {
        static StatementView of(AllianceStatement s) {
            return new StatementView(s.getId(), s.getPeriod(), s.getItemCount(), s.getFaceTotalMinor(), s.getSettleTotalMinor(),
                    s.getStatus(), s.getConfirmDueAt(), s.getConfirmedAt(), s.isAutoConfirmed(), s.getObjectionNote(),
                    s.getPlatformNote(), s.getPayeeAccountType(), s.getPayeeAccountName(), s.getPayeeBankName(),
                    s.getPayeeBranchName(), s.getPayeeAccountLast4(), s.getPaidReference(), s.getPaidDate(), s.getCreatedAt());
        }
    }

    public record StatementLine(Long settlementId, String productName, long faceValueMinor, long settleAmountMinor,
                                Instant redeemedAt) {}

    public record StatementDetail(StatementView statement, List<StatementLine> lines) {}

    public record PlatformAccount(Long tenantId, String supplierName, String registeredCompanyName, AccountView account) {}

    public record PlatformStatement(Long tenantId, String supplierName, String accountStatus, StatementView statement) {}

    // ---------- 供券商户 ----------

    public record OfferView(Long id, Long storeId, String storeName, Long productId, String productName, String saleUnit,
                            long faceValueMinor, Integer settleRateBp, Long settleAmountMinor, int totalQty, int claimedQty,
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

    /** state：IN_DISPUTE_WINDOW 申诉期 / FROZEN 申诉处理中 / PAYABLE 待结算 / IN_STATEMENT 已入对账单 / PAID 已结算 / VOID 已作废。 */
    public record SettlementView(Long id, String productName, long faceValueMinor, long settleAmountMinor, String state,
                                 Instant payableAfter, Instant paidAt, String paidReference, Instant createdAt) {
        static SettlementView of(AllianceSettlement s, Instant now) {
            return new SettlementView(s.getId(), s.getProductName(), s.getFaceValueMinor(), s.getSettleAmountMinor(),
                    state(s, now), s.getPayableAfter(), s.getPaidAt(), s.getPaidReference(), s.getCreatedAt());
        }

        static String state(AllianceSettlement s, Instant now) {
            if (!"HOLDING".equals(s.getStatus())) return s.getStatus();
            if (s.getStatementId() != null) return "IN_STATEMENT";
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

    /** accountStatus：供券商户结算账户的状态，没填为 null，审核供券单时一并看。 */
    public record PlatformOffer(Long tenantId, String supplierName, String supplierCreditCode, String storeAddress,
                                String accountStatus, OfferView offer) {}

    public record PlatformDispute(Long holderTenantId, String holderName, Long supplierTenantId, UUID ref,
                                  String productName, String storeName, long faceValueMinor, Instant redeemedAt,
                                  String disputeReason, Instant disputedAt) {}

    public record Summary(long grantedMinor, long claimedFaceMinor, long redeemedFaceMinor, long expiredFaceMinor,
                          long holdingSettleMinor, long paidSettleMinor, long pendingOffers, long openDisputes) {}
}
