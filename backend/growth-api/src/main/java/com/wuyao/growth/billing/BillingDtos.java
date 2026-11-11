package com.wuyao.growth.billing;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class BillingDtos {
    private BillingDtos() {}
    public record PlanSnapshot(Long id, String code, String name, String description, long priceMinor,
                               int durationDays, long giftCredits, boolean imageEnabled, boolean videoEnabled,
                               boolean liveEnabled, long allianceQuotaMinor) {}
    public record Summary(String currencyName, int creditsPerYuan, int imageCredits, int videoAnalysisCredits,
                          int liveCreditsPerHour, long available,
                          long giftAvailable, long purchasedAvailable, long held, boolean memberActive,
                          Instant memberUntil, PlanSnapshot plan, boolean canManage, boolean operator,
                          PaymentInstructions payment, String paymentMethod) {}
    public record PaymentInstructions(boolean available, String recipient, String account, String bank,
                                      String instructions, String qrCodeUrl) {}
    public record TopUp(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                        @Min(50000) @Max(10000000) long amountMinor) {}
    public record Subscribe(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                            @NotNull @Positive Long planId) {}
    public record NewPlan(@NotBlank @Pattern(regexp="[A-Z0-9_-]{2,60}") String code,
                          @NotBlank @Size(max=100) String name, @NotNull @Size(max=600) String description,
                          @Min(1) @Max(100000000) long priceMinor, @Min(1) @Max(3660) int durationDays,
                          @Min(0) @Max(100000000) long giftCredits, boolean imageEnabled, boolean videoEnabled,
                          boolean liveEnabled, boolean published, @Min(0) @Max(100000000) long allianceQuotaMinor,
                          boolean replacePublished) {
        public NewPlan(String code, String name, String description, long priceMinor, int durationDays, long giftCredits,
                       boolean imageEnabled, boolean videoEnabled, boolean liveEnabled, boolean published) {
            this(code, name, description, priceMinor, durationDays, giftCredits, imageEnabled, videoEnabled, liveEnabled, published, 0, false);
        }
        public NewPlan(String code, String name, String description, long priceMinor, int durationDays, long giftCredits,
                       boolean imageEnabled, boolean videoEnabled, boolean liveEnabled, boolean published, long allianceQuotaMinor) {
            this(code, name, description, priceMinor, durationDays, giftCredits, imageEnabled, videoEnabled, liveEnabled, published,
                    allianceQuotaMinor, false);
        }
    }
    /** 会员订单入账、会员期确定后发出，与入账同一事务。联盟券模块据此发放本期额度。 */
    public record MembershipActivated(Long tenantId, String billingOrderId, PlanSnapshot plan,
                                      Instant periodStart, Instant periodEnd) {}
    /**
     * 充值或会员订单实际付款入账后发出，与入账同一事务；kind 为 TOPUP 或 MEMBERSHIP。邀请返利模块据此给邀请人记返利。
     * 有实收金额的充值兑换码兑换后也发出，billingOrderId 为 redeem-code-{兑换码编号}。
     */
    public record OrderPaid(Long tenantId, String billingOrderId, String kind, long amountMinor) {}
    /** replacePublished：上架时把同一标识正在上架的版本一起下架，商户那边不断档。 */
    public record Publication(boolean published, boolean replacePublished) {
        public Publication(boolean published) { this(published, false); }
    }
    public record PlanListing(PlanSnapshot plan, boolean published, Instant createdAt) {}
    public record Confirmation(@Min(1) long receivedAmountMinor,
                               @NotBlank @Size(min=6,max=150) String receiptReference) {}
    public record LedgerView(Long id, String type, String description, long availableDelta, long heldDelta,
                             long availableAfter, long heldAfter, Instant createdAt, Long actorUserId) {}
    /** kind 为 TOPUP、MEMBERSHIP 或 ACTIVATION_CODE；activationCode 只在激活码订单付款后才有。 */
    public record OrderView(String id, String kind, long amountMinor, long credits, String state,
                            PlanSnapshot plan, Instant createdAt, Instant expiresAt, Instant paidAt,
                            PaymentInstructions payment, String paymentMethod, String paymentCodeUrl,
                            Long createdBy, Long confirmedBy, String activationCode) {
        public OrderView(String id, String kind, long amountMinor, long credits, String state, PlanSnapshot plan, Instant createdAt,
                         Instant expiresAt, Instant paidAt, PaymentInstructions payment, String paymentMethod, String paymentCodeUrl,
                         Long createdBy, Long confirmedBy) {
            this(id, kind, amountMinor, credits, state, plan, createdAt, expiresAt, paidAt, payment, paymentMethod, paymentCodeUrl,
                    createdBy, confirmedBy, null);
        }
    }
    /** 激活码订单付款入账后发出，与入账同一事务；兑换码模块据此生成这笔订单的激活码（一笔一个）。 */
    public record ActivationCodePurchased(Long tenantId, String orderId, Long planId, Long buyerUserId) {}
    /** 本商户在站内买的激活码；status 同兑换码。不显示兑换方是谁。 */
    public record PurchasedCode(String code, String planName, Integer durationDays, String status, Instant createdAt,
                                Instant redeemedAt, String orderId) {}
    /** A paid top-up with the balance read from its ledger entry, not recomputed from today's balance. */
    public record TopupRecord(String orderId, Instant paidAt, long amountMinor, long credits, String paymentMethod,
                              long balanceBefore, long balanceAfter) {}
    public record History<T>(List<T> items, boolean hasMore) {}

    /** 全平台烟火流水的一行；availableDelta 是可用烟火变动（赠送 + 购买），heldDelta 是预占变动。 */
    public record PlatformLedgerEntry(Long id, Long tenantId, String tenantName, Long actorUserId, String actorName,
                                      String type, String capability, String description, long availableDelta,
                                      long heldDelta, long availableAfter, Instant createdAt, String model,
                                      Long elapsedMillis, String taskStatus, Long previewElapsedMillis) {}

    /** 时间窗口内的全平台合计；consumed 只算实际扣除（CAPTURE），预占不算消耗。 */
    public record PlatformLedgerSummary(int days, long consumed, long consumedToday, long refunded, long purchased,
                                        long granted, java.util.Map<String, Long> consumedByCapability) {}

    /** 某一天（北京时间）的全平台合计，口径同 PlatformLedgerSummary；没有流水的日子各项为 0。 */
    public record DailyCredits(java.time.LocalDate date, long consumed, long refunded, long purchased, long granted,
                               java.util.Map<String, Long> consumedByCapability) {}

    /**
     * 批量生成兑换码；kind 为 TOPUP（记充值烟火）、GIFT（记赠送烟火）或 MEMBERSHIP（激活码，按 planId 开通会员），
     * expiresAt 为空表示长期有效。amountMinor 是每个充值码的实收金额（分），兑换后按它记邀请返利，0 表示不记；
     * 赠送码和激活码必须为 0。激活码的 credits 为 0，planId 必须是已上架的套餐；其他种类不带 planId。
     */
    public record NewRedeemCodes(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                                 @NotBlank @Pattern(regexp="TOPUP|GIFT|MEMBERSHIP") String kind,
                                 @Min(0) @Max(100000) long credits, @Min(0) @Max(10000000) long amountMinor,
                                 @Min(1) @Max(200) int quantity,
                                 Instant expiresAt, @Size(max=200) String note, @Positive Long planId) {
        public NewRedeemCodes(String requestKey, String kind, long credits, long amountMinor, int quantity, Instant expiresAt, String note) {
            this(requestKey, kind, credits, amountMinor, quantity, expiresAt, note, null);
        }
    }
    /** status 为 UNUSED、REDEEMED、VOIDED 或 EXPIRED；planName、durationDays 只有激活码才有。 */
    public record RedeemCodeView(Long id, String code, String kind, long credits, long amountMinor, String status, String note,
                                 Instant expiresAt, Instant createdAt, Long redeemedTenantId, String redeemedTenantName,
                                 Instant redeemedAt, Instant voidedAt, Long planId, String planName, Integer durationDays, Long partnerId) {}
    public record Redeem(@NotBlank @Size(max=40) String code) {}
    /** 服务商生成的激活码被兑换后发出，与兑换同一事务；服务商模块据此把商户归到服务商名下（第一次归属为准）。 */
    public record PartnerCodeRedeemed(Long tenantId, Long codeId, Long partnerId) {}
    /** 激活码兑换后 planName、memberUntil 为开通的套餐和新的到期时间，credits 为 0。 */
    public record Redeemed(String kind, long credits, long available, String planName, Instant memberUntil) {}

    /**
     * 烟火看板，全部为累计数。purchasedBySource / grantedBySource 按来源拆分：
     * ORDER（在线付款：充值或会员赠送）、ADMIN（平台赠送）、ADMIN_MEMBERSHIP（平台开通会员）、REDEEM_CODE（兑换码）、
     * PARTNER（服务商分给商户的烟火）。
     */
    public record CreditOverview(long purchased, long granted, long consumed, long available, long held,
                                 java.util.Map<String, Long> purchasedBySource, java.util.Map<String, Long> grantedBySource,
                                 List<MerchantCredits> merchants) {}
    /** 一个商户的当前余额与累计充值、赠送、消耗；消耗只算实际扣除。 */
    public record MerchantCredits(Long tenantId, String tenantName, long giftAvailable, long purchasedAvailable, long held,
                                  long purchased, long granted, long consumed) {}
}
