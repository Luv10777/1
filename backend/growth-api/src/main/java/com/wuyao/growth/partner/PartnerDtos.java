package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 服务商后台和平台管理服务商用到的入参出参。 */
public final class PartnerDtos {
    private PartnerDtos() {}

    /** creditsAvailable 只给负责人和平台看，BD 看到的是空。 */
    public record Partner(Long id, String name, String contact, String status, Long creditsAvailable, Instant createdAt) {}
    /** 一个套餐标识的激活码额度；planName、durationDays 取该标识最新的版本。 */
    public record Quota(String planCode, String planName, Integer durationDays, int quota, int used, int remaining) {}
    /** role 是当前登录账号在服务商里的角色：OWNER 负责人、BD。BD 的 tenantCount 只算自己名下的。 */
    public record Overview(Partner partner, List<Quota> quotas, long tenantCount, String role) {}
    /** 激活码统计：unused = generated - redeemed - voided；renewals 是其中直接续期的个数。 */
    public record CodeStats(long generated, long redeemed, long unused, long voided, long renewals) {}
    /** 服务商的登录账号；role 为 OWNER 或 BD，tenantCount 是归到这个 BD 的商户数（负责人为空）。 */
    public record Member(Long userId, Long uid, String name, String phone, String role, Long tenantCount, CodeStats codes, Instant createdAt) {}
    /**
     * 按 UID 预查询，添加商户或 BD 前确认对象。userName 打码；tenantState：NONE 没有归属、MINE 在我名下、
     * TEAM 在本服务商但归别人管、OTHER 属于其他服务商（不透露是哪家）。tenantId 只在 MINE 时给出。
     * memberState 只给负责人：NONE、OWNER、BD（本服务商的登录账号）、OTHER（其他服务商的登录账号）。
     */
    public record Lookup(Long uid, String userName, String tenantName, String tenantState, Long tenantId, String bdName, String memberState) {}
    /**
     * 商户详情：transfers 是这家服务商分给他的烟火（只给负责人，BD 为空），codes 是他兑换过的本服务商激活码（含直接续期），
     * usage 是最近 30 天的使用情况，onboarding 是上手进度。
     */
    public record TenantDetail(TenantView tenant, List<LedgerEntry> transfers, List<BillingDtos.RedeemCodeView> codes,
                               Usage usage, Onboarding onboarding) {}
    /**
     * 商户使用情况：只有烟火数和使用次数，不含作品内容。consumed、uses 和按功能的分组是最近 days 天；
     * lowBalance 表示可用烟火不到最近一笔充值的 10%。
     */
    public record Usage(int days, long consumed, long uses, java.util.Map<String, Long> consumedByCapability,
                        java.util.Map<String, Long> usesByCapability, Instant firstUsedAt, Instant lastUsedAt,
                        long lastTopUpCredits, Instant lastTopUpAt, boolean lowBalance) {}
    /** 上手进度，由系统按商户的真实数据判断：开通过会员、建了门店、上传了商品、用过一次 AI 功能。 */
    public record Onboarding(boolean activated, long stores, long products, boolean firstUse) {}
    /**
     * 待办：type 为 EXPIRING（7 天内到期）、EXPIRED（30 天内刚过期）、LOW_BALANCE（余额不到最近充值的 10%）、
     * NOT_STARTED（开通了还没用过）、IDLE（会员有效但 7 天没用）。同一个商户可以出现在几类里。
     */
    public record Todo(String type, TenantView tenant, Instant lastUsedAt, long lastTopUpCredits) {}
    public record Workbench(List<Todo> todos, java.util.Map<String, Long> counts, Instant checkedAt) {}
    /** 看板每天一个点（北京时间）：商户消耗、分给商户的烟火、新增商户、激活码兑换（含直接续期）。 */
    public record DailyPoint(String date, long consumed, long transferred, long newTenants, long redeemed) {}
    public record TenantRank(Long tenantId, String tenantName, String bdName, long consumed, long uses, Instant lastUsedAt) {}
    public record BdRank(Long userId, String name, long tenants, long consumed, long activeTenants, long codesRedeemed, long renewals) {}
    /**
     * 经营看板（最近 days 天，days=1 是今天）。granted、transferred、creditsAvailable 和 bds 只给负责人，BD 为空。
     * activeTenants 是这段时间用过 AI 功能的商户，firstUses 是这段时间第一次使用的商户。
     */
    public record Dashboard(int days, Long creditsAvailable, long tenants, long newTenants, long activeMembers, long expiringSoon,
                            long activeTenants, long firstUses, long consumed, long uses, java.util.Map<String, Long> consumedByCapability,
                            Long granted, Long transferred, long codesGenerated, long codesRedeemed, long renewals, long codesVoided,
                            List<DailyPoint> daily, List<TenantRank> topTenants, List<BdRank> bds) {}
    /** 名下商户；会员和余额读不到时为空。source 为 UID、CODE 或 PLATFORM；bdUserId 为空表示负责人直接管。 */
    public record TenantView(Long tenantId, String tenantName, Long ownerUid, String ownerName, String source, Instant boundAt,
                             Boolean memberActive, Instant memberUntil, String planName, Long creditsAvailable,
                             Long bdUserId, String bdName) {}
    /** 服务商烟火流水：GRANT 平台发放（正），TRANSFER 分给名下商户（负）。 */
    public record LedgerEntry(Long id, String type, long delta, long availableAfter, Long tenantId, String tenantName,
                              String note, Instant createdAt) {}
    public record Page<T>(List<T> items, int page, int size, long total) {}

    public record UidRequest(@NotNull @Min(10000000) @Max(99999999) Long uid) {}
    public record Transfer(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                           @Min(1) @Max(1000000) long credits, @Size(max=200) String note) {}
    public record NewCodes(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,60}") String requestKey,
                           @NotBlank @Pattern(regexp="[A-Z0-9_-]{2,60}") String planCode,
                           @Min(1) @Max(200) int quantity, @Size(max=200) String note) {}
    /** 用一个激活码额度直接给名下商户开通或顺延会员。 */
    public record Renew(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,60}") String requestKey,
                        @NotBlank @Pattern(regexp="[A-Z0-9_-]{2,60}") String planCode) {}
    /** 负责人把商户调给某个 BD；bdUserId 为空表示收回由负责人直接管。 */
    public record AssignBd(@Positive Long bdUserId) {}

    public record PlatformPartner(Partner partner, List<Member> members, List<Quota> quotas, long tenantCount) {}
    public record NewPartner(@NotBlank @Size(max=80) String name, @Size(max=120) String contact,
                             @NotNull @Min(10000000) @Max(99999999) Long adminUid) {}
    public record Grant(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                        @Min(1) @Max(10000000) long credits, @NotBlank @Size(max=200) String note) {}
    public record SetQuota(@NotBlank @Pattern(regexp="[A-Z0-9_-]{2,60}") String planCode, @Min(0) @Max(1000000) int quota) {}
    public record SetStatus(@NotBlank @Pattern(regexp="ACTIVE|DISABLED") String status) {}
    /** partnerId 为空表示解除归属。 */
    public record Reassign(@Positive Long partnerId) {}
}
