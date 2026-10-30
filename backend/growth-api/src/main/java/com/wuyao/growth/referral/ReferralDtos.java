package com.wuyao.growth.referral;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ReferralDtos {
    private ReferralDtos() {}

    /** 「我的邀请」。code 为空表示还没领邀请码；金额单位都是分。 */
    public record Overview(boolean realNameVerified, String code, String agreementVersion, int topupRateBp, int membershipRateBp, int freezeDays,
                           long inviteeCount, long frozenMinor, long availableMinor,
                           List<Invitee> invitees, List<Commission> commissions) {}

    public record Invitee(String phoneMasked, Instant registeredAt) {}

    /** kind：TOPUP 充值烟火，MEMBERSHIP 开通会员。state：FROZEN 冻结中，AVAILABLE 可提现，VOIDED 已作废。 */
    public record Commission(Long id, String inviteePhoneMasked, String kind, long paidMinor, int rateBp, long amountMinor,
                             String state, Instant availableAt, Instant createdAt) {}

    public record CreateCode(@NotNull @AssertTrue(message = "请阅读并同意《推广协议》") Boolean agreementAccepted) {}

    public record RateView(int topupRateBp, int membershipRateBp, List<RateChange> history) {}

    public record RateChange(String kind, int rateBp, String note, Long createdBy, Instant createdAt) {}

    /** 后台「邀请关系」：每个商户一行，没有邀请人的 referrer* 为空，即归属公司。金额只统计已记返利的订单。 */
    public record AdminRelation(Long tenantId, String tenantName, String ownerName, String ownerPhoneMasked, Instant registeredAt,
                                Long referrerUserId, String referrerName, String referrerPhoneMasked, String referrerTenantName,
                                String code, Instant boundAt, long topupPaidMinor, long membershipPaidMinor, long commissionMinor) {}

    /** 后台「返利明细」的一行。 */
    public record AdminCommission(Long id, Long referrerUserId, String referrerName, String referrerPhoneMasked,
                                  Long inviteeTenantId, String inviteeTenantName, String inviteePhoneMasked, String kind,
                                  long paidMinor, int rateBp, long amountMinor, String state, Instant availableAt, Instant createdAt) {}

    /** 返利明细一页，外加筛选范围内冻结中、可提现的合计。 */
    public record AdminCommissionPage(com.wuyao.growth.common.web.PageResult<AdminCommission> page, long frozenMinor, long availableMinor) {}

    /** 后台「推广人排行」：领过邀请码的人。payingInviteeCount 是已经产生返利的被邀请商户数。 */
    public record AdminPromoter(Long userId, String name, String phoneMasked, String tenantName, String code,
                                long inviteeCount, long payingInviteeCount, long commissionMinor, long frozenMinor, long availableMinor,
                                Instant codeCreatedAt) {}

    public record UpdateRate(
            @NotBlank @Pattern(regexp = "TOPUP|MEMBERSHIP", message = "返利类型不正确") String kind,
            @Min(value = 0, message = "返利比例不能小于 0") @Max(value = 5000, message = "返利比例不能超过 50%") int rateBp,
            @NotBlank(message = "请填写调整原因") @Size(min = 5, max = 200, message = "调整原因需为 5–200 字") String note) {}
}
