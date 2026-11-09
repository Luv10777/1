package com.wuyao.growth.community;

import java.time.Instant;
import java.time.LocalDate;

public final class PlatformDtos {
    private PlatformDtos() {}

    public record InformationView(Long id, Long tenantId, String tenantName, String category,
                                  String title, String merchantName, String region, String summary,
                                  String content, String contactName, String contactPhone,
                                  LocalDate validUntil, String status, Instant publishedAt, Instant createdAt,
                                  String reviewNote, Instant updatedAt) {}

    public record MerchantView(Long id, Long tenantId, String tenantName, String merchantName,
                               String companyName, String industry, String region, String creditCodeMasked,
                               String contactName, String contactPhoneMasked, String introduction,
                               String status, String reviewNote, Instant createdAt, Instant updatedAt) {}

    public record Summary(long publishedInformation, long offlineInformation, long pendingMerchants,
                          long approvedMerchants, long pendingIdentity, long pendingInformation) {}

    public record MerchantDetailView(Long id, String creditCode, String contactPhone) {}

    /**
     * 平台用户列表的一行；手机号脱敏，会员与烟火取自用户所属商户。
     * identity 只在实名核验通过时给出（只含脱敏姓名与证件号），未实名为 null。
     */
    public record UserView(Long id, Long tenantId, String tenantName, String name, String phoneMasked, String username,
                           String role, String status, Instant lastLoginAt, Instant createdAt, Instant suspendedAt,
                           boolean memberActive, Instant memberUntil, String planName, long creditsAvailable,
                           boolean operator, UserIdentity identity) {}

    /** 已通过的实名记录；id 是实名申请编号，按用途查看完整资料时使用。 */
    public record UserIdentity(Long id, String legalNameMasked, String idNumberMasked, Instant verifiedAt) {}

    /**
     * 用户概况，带上一个 7 天的对比值：usersWeekAgo 是 7 天前已注册的账号数，newPrevious7Days 是再往前 7 天的新增数，
     * activePrevious7Days 是再往前 7 天登录过的账号数，登录记录还不满 14 天时为 null。
     */
    public record UserSummary(long users, long activeLast7Days, long newLast7Days, long suspended,
                              long usersWeekAgo, long newPrevious7Days, Long activePrevious7Days) {}

    public record UserSuspensionRequest(
            @jakarta.validation.constraints.NotNull Boolean suspended,
            @jakarta.validation.constraints.Size(max = 500) String note) {}

    /** granted=true 时按 planId 开通或续期；false 时立即关闭。 */
    public record MembershipRequest(
            @jakarta.validation.constraints.NotNull Boolean granted,
            Long planId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String requestKey,
            @jakarta.validation.constraints.Size(max = 300) String note) {}

    public record CreditGrantRequest(
            @jakarta.validation.constraints.NotNull Long credits,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String requestKey,
            @jakarta.validation.constraints.Size(max = 300) String note) {}

    public record ReviewRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "PUBLISHED|REJECTED|OFFLINE", message = "信息状态不正确") String status,
            @jakarta.validation.constraints.Size(max = 500) String note,
            @jakarta.validation.constraints.NotNull Instant updatedAt) {}

    public record MerchantReviewRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "APPROVED|REJECTED|SUSPENDED", message = "商家状态不正确") String status,
            @jakarta.validation.constraints.Size(max = 500) String note) {}
}
