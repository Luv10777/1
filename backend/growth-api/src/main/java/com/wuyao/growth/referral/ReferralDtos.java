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

    public record UpdateRate(
            @NotBlank @Pattern(regexp = "TOPUP|MEMBERSHIP", message = "返利类型不正确") String kind,
            @Min(value = 0, message = "返利比例不能小于 0") @Max(value = 5000, message = "返利比例不能超过 50%") int rateBp,
            @NotBlank(message = "请填写调整原因") @Size(min = 5, max = 200, message = "调整原因需为 5–200 字") String note) {}
}
