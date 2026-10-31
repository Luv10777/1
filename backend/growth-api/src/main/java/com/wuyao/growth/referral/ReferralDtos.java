package com.wuyao.growth.referral;

import com.wuyao.growth.community.BankCardDtos;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReferralDtos {
    private ReferralDtos() {}

    /**
     * 「我的邀请」。code 为空表示还没领邀请码；金额单位都是分。owedMinor 是已打款后又作废、下次提现要扣回的金额；
     * bankCard 为空表示还没绑定收款银行卡。
     */
    public record Overview(boolean realNameVerified, String code, String agreementVersion, int topupRateBp, int membershipRateBp, int freezeDays,
                           long inviteeCount, long frozenMinor, long availableMinor, long owedMinor, long withdrawMinimumMinor,
                           BankCardDtos.CardView bankCard, List<Invitee> invitees, List<Commission> commissions, List<Withdrawal> withdrawals) {}

    public record Invitee(String phoneMasked, Instant registeredAt) {}

    /**
     * kind：TOPUP 充值烟火，MEMBERSHIP 开通会员。
     * state：FROZEN 冻结中，AVAILABLE 可提现，WITHDRAWING 提现审核中，WITHDRAWN 已提现，VOIDED 已作废（voidNote 是原因）。
     */
    public record Commission(Long id, String inviteePhoneMasked, String kind, long paidMinor, int rateBp, long amountMinor,
                             String state, String voidNote, Instant availableAt, Instant createdAt) {}

    /** 提现单。status：PENDING 待审核，REJECTED 已驳回（reviewNote 是原因），PAID 已打款。税额和实发在打款后才有。 */
    public record Withdrawal(Long id, long commissionMinor, long clawbackMinor, long amountMinor, String status,
                             String bankName, String cardLast4, String reviewNote, Long taxMinor, Long netMinor,
                             LocalDate paidDate, Instant createdAt, Instant reviewedAt) {}

    public record CreateCode(@NotNull @AssertTrue(message = "请阅读并同意《推广协议》") Boolean agreementAccepted) {}

    public record ApplyWithdrawal(@NotNull @AssertTrue(message = "请阅读并同意《推广协议》中的提现规则") Boolean agreementAccepted) {}

    public record RateView(int topupRateBp, int membershipRateBp, List<RateChange> history) {}

    public record RateChange(String kind, int rateBp, String note, Long createdBy, Instant createdAt) {}

    /** 后台「邀请关系」：每个商户一行，没有邀请人的 referrer* 为空，即归属公司。金额只统计已记返利的订单。 */
    public record AdminRelation(Long tenantId, String tenantName, String ownerName, String ownerPhoneMasked, Instant registeredAt,
                                Long referrerUserId, String referrerName, String referrerPhoneMasked, String referrerTenantName,
                                String code, Instant boundAt, long topupPaidMinor, long membershipPaidMinor, long commissionMinor) {}

    /** 后台「返利明细」的一行。tenantId 是推广人所在商户，作废时用。 */
    public record AdminCommission(Long id, Long tenantId, Long referrerUserId, String referrerName, String referrerPhoneMasked,
                                  Long inviteeTenantId, String inviteeTenantName, String inviteePhoneMasked, String kind,
                                  long paidMinor, int rateBp, long amountMinor, String state, String voidNote,
                                  Instant availableAt, Instant createdAt) {}

    /** 返利明细一页，外加筛选范围内冻结中、可提现的合计。 */
    public record AdminCommissionPage(com.wuyao.growth.common.web.PageResult<AdminCommission> page, long frozenMinor, long availableMinor) {}

    /** 后台「推广人排行」：领过邀请码的人。payingInviteeCount 是已经产生返利的被邀请商户数。 */
    public record AdminPromoter(Long userId, String name, String phoneMasked, String tenantName, String code,
                                long inviteeCount, long payingInviteeCount, long commissionMinor, long frozenMinor, long availableMinor,
                                Instant codeCreatedAt) {}

    /** 后台「提现审核」的一行。 */
    public record AdminWithdrawal(Long id, Long tenantId, String tenantName, Long userId, String userName, String phoneMasked,
                                  long commissionMinor, long clawbackMinor, long amountMinor, String status, String bankName,
                                  String cardLast4, String reviewNote, Long taxMinor, Long netMinor, String paidReference,
                                  LocalDate paidDate, Instant createdAt, Instant reviewedAt) {}

    /** 提现单一页，外加全部待审核的笔数和金额。 */
    public record AdminWithdrawalPage(com.wuyao.growth.common.web.PageResult<AdminWithdrawal> page, long pendingCount, long pendingMinor) {}

    public record RevealPayee(@NotBlank(message = "请写明查看用途") @Size(min = 5, max = 200, message = "查看用途需为 5–200 字") String purpose) {}

    public record RejectWithdrawal(@NotBlank(message = "请写明驳回原因") @Size(min = 2, max = 200, message = "驳回原因需为 2–200 字") String note) {}

    /** 财务转账后登记：代扣税额由财务按实际计算后填写，系统不计算个税。 */
    public record PayWithdrawal(
            @NotNull(message = "请填写代扣税额") @Min(value = 0, message = "代扣税额不能小于 0") Long taxMinor,
            @NotBlank(message = "请填写转账流水号") @Size(min = 4, max = 100, message = "转账流水号需为 4–100 字") String reference,
            @NotNull(message = "请填写打款日期") LocalDate paidDate) {}

    public record VoidCommission(@NotBlank(message = "请写明作废原因") @Size(min = 5, max = 200, message = "作废原因需为 5–200 字") String note) {}

    public record UpdateRate(
            @NotBlank @Pattern(regexp = "TOPUP|MEMBERSHIP", message = "返利类型不正确") String kind,
            @Min(value = 0, message = "返利比例不能小于 0") @Max(value = 5000, message = "返利比例不能超过 50%") int rateBp,
            @NotBlank(message = "请填写调整原因") @Size(min = 5, max = 200, message = "调整原因需为 5–200 字") String note) {}
}
