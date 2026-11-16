package com.wuyao.growth.partner;

import com.wuyao.growth.billing.BillingDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 服务商后台和平台管理服务商用到的入参出参。 */
public final class PartnerDtos {
    private PartnerDtos() {}

    /** creditsAvailable 是团队账户的可用烟火（读不到时为空）。 */
    public record Partner(Long id, String name, String contact, String status, Long creditsAvailable, Instant createdAt) {}
    /** 服务商能生成激活码的套餐（不限个数），名字和天数取该标识最新的版本。 */
    public record Plan(String planCode, String planName, Integer durationDays) {}
    /** 成员的权限：商户、激活码、分配烟火、财务查看、团队管理。负责人全部为 true。 */
    public record Permissions(boolean tenants, boolean codes, boolean distribute, boolean finance, boolean team) {
        static Permissions of(PartnerStore.Perms p) { return new Permissions(p.tenants(), p.codes(), p.distribute(), p.finance(), p.team()); }
        PartnerStore.Perms perms() { return new PartnerStore.Perms(tenants, codes, distribute, finance, team); }
    }
    /**
     * 当前登录账号看到的服务商。role 为 OWNER（负责人）或 MEMBER（成员）；displayName 是他在团队里的称呼，为空时前端先请他填；
     * account 是团队账户的烟火（成员在一方志里创作和分给商户都扣它）；monthCodes 是本月（北京时间）这家服务商生成的激活码个数。
     */
    public record Overview(Partner partner, List<Plan> plans, long tenantCount, String role, Permissions permissions,
                           String displayName, String userName, Account account, long monthCodes) {}
    /** 团队账户的烟火：可用 = 赠送 + 充值。 */
    public record Account(long available, long gift, long purchased) {}
    /** 一方志站内判断要不要显示「服务商后台」入口：不是服务商成员时 member 为 false，其余为空。 */
    public record Membership(boolean member, String partnerName, String role, boolean active) {}
    /**
     * 一笔分配记录。source：POOL（V84 以前的可分配烟火）或 ACCOUNT（团队账户）；recipientType：TENANT（V84 以前还有 BD）；
     * gift、purchased 是对方收到的赠送和充值烟火；status 为 DONE（已到账）或 PENDING（已扣团队账户，对方还没入账，用同一请求重试即可补上）。
     */
    public record Distribution(Long id, String source, String recipientType, Long recipientTenantId, String recipientName,
                               Long recipientUserId, long credits, long gift, long purchased, String note, Long actorUserId,
                               String actorName, String status, Instant createdAt) {}
    /** 激活码统计：unused = generated - redeemed - voided；renewals 是其中直接续期的个数。 */
    public record CodeStats(long generated, long redeemed, long unused, long voided, long renewals) {}
    /**
     * 服务商的登录账号。name 是一方志账号名，displayName 是团队里的称呼（没填时为空）；phone 只给有团队管理权限的人看；
     * monthlyCap 是每月最多分出的烟火（空表示不限），distributedThisMonth 是本月已分出的；tenantsAdded 是他按 UID 加进来的商户数。
     */
    public record Member(Long userId, Long uid, String name, String displayName, String phone, String role, Permissions permissions,
                         Long monthlyCap, long distributedThisMonth, long tenantsAdded, CodeStats codes, Instant createdAt) {}
    /**
     * 按 UID 预查询，添加商户前确认对象。userName 打码；tenantState：NONE 没有归属、MINE 在本服务商名下、
     * OTHER 属于其他服务商（不透露是哪家）。tenantId 只在 MINE 时给出。
     */
    public record Lookup(Long uid, String userName, String tenantName, String tenantState, Long tenantId) {}
    /**
     * 商户详情：transfers 是这家服务商分给他的烟火（有分配或财务权限的人可见，否则为空），codes 是他兑换过的本服务商激活码（含直接续期），
     * usage 是最近 30 天的使用情况，onboarding 是上手进度。
     */
    public record TenantDetail(TenantView tenant, List<Distribution> transfers, List<BillingDtos.RedeemCodeView> codes,
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
    public record TenantRank(Long tenantId, String tenantName, long consumed, long uses, Instant lastUsedAt) {}
    /** 成员这段时间做的事：按 UID 加的商户、生成和被兑换的激活码、直接续期、分出去的烟火（没有财务权限的人看到的是空）。 */
    public record MemberRank(Long userId, String name, long tenantsAdded, long codesGenerated, long codesRedeemed, long renewals,
                             Long distributed) {}
    /**
     * 经营看板（最近 days 天，days=1 是今天）。creditsAvailable、granted、transferred 只给有财务权限的人，其余为空。
     * activeTenants 是这段时间用过 AI 功能的商户，firstUses 是这段时间第一次使用的商户。
     * 会员状态按现在算：activeMembers（含 7 天内到期的 expiringSoon）、expiredMembers（开通过已过期）、neverActivated（从未开通）。
     */
    public record Dashboard(int days, Long creditsAvailable, long tenants, long newTenants, long activeMembers, long expiringSoon,
                            long expiredMembers, long neverActivated,
                            long activeTenants, long firstUses, long consumed, long uses, java.util.Map<String, Long> consumedByCapability,
                            Long granted, Long transferred, long codesGenerated, long codesRedeemed, long renewals, long codesVoided,
                            List<DailyPoint> daily, List<TenantRank> topTenants, List<MemberRank> members) {}
    /** 名下商户；会员和余额读不到时为空。source 为 UID、CODE 或 PLATFORM；addedByName 是按 UID 添加他的成员。 */
    public record TenantView(Long tenantId, String tenantName, Long ownerUid, String ownerName, String source, Instant boundAt,
                             Boolean memberActive, Instant memberUntil, String planName, Long creditsAvailable, String addedByName) {}
    /**
     * 服务商流水：GRANT 平台发放（V84 起直接进团队账户，availableAfter 为空）；TRANSFER 是 V84 以前从可分配烟火分给商户的，
     * 以及 V84 把剩余的可分配烟火转进团队账户的那一笔。
     */
    public record LedgerEntry(Long id, String type, long delta, Long availableAfter, Long tenantId, String tenantName,
                              String note, String actorName, Instant createdAt) {}
    public record Page<T>(List<T> items, int page, int size, long total) {}

    public record UidRequest(@NotNull @Min(10000000) @Max(99999999) Long uid) {}
    public record Transfer(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                           @Min(1) @Max(1000000) long credits, @Size(max=200) String note) {}
    public record NewCodes(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,60}") String requestKey,
                           @NotBlank @Pattern(regexp="[A-Z0-9_-]{2,60}") String planCode,
                           @Min(1) @Max(200) int quantity, @Size(max=200) String note) {}
    /** 直接给名下商户开通或顺延会员（不扣额度，激活码不限个数）。 */
    public record Renew(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,60}") String requestKey,
                        @NotBlank @Pattern(regexp="[A-Z0-9_-]{2,60}") String planCode) {}
    /** 按手机号加成员：没注册过的在团队账户里新建一个员工账号，name 是账号名；已是团队账户员工的直接加进来。 */
    public record NewMember(@NotBlank @Size(max=20) String phone, @NotBlank @Size(max=20) String name,
                            @NotNull @Valid Permissions permissions, @Positive @Max(100000000) Long monthlyCap) {}
    /** 改成员：权限、每月分配上限（空表示不限）、团队称呼（空表示不改）。 */
    public record UpdateMember(@NotNull @Valid Permissions permissions, @Positive @Max(100000000) Long monthlyCap,
                               @Size(max=20) String displayName) {}
    public record DisplayName(@NotBlank @Size(max=20) String displayName) {}

    /** teamTenantName 是团队账户（负责人的一方志商户）的名字。 */
    public record PlatformPartner(Partner partner, List<Member> members, long tenantCount, Long teamTenantId, String teamTenantName) {}
    public record NewPartner(@NotBlank @Size(max=80) String name, @Size(max=120) String contact,
                             @NotNull @Min(10000000) @Max(99999999) Long adminUid) {}
    public record Grant(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                        @Min(1) @Max(10000000) long credits, @NotBlank @Size(max=200) String note) {}
    public record Rename(@NotBlank @Size(max=80) String name, @Size(max=120) String contact) {}
    public record SetStatus(@NotBlank @Pattern(regexp="ACTIVE|DISABLED") String status) {}
    /** partnerId 为空表示解除归属。 */
    public record Reassign(@Positive Long partnerId) {}
}
