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

    /** 平台用户列表的一行；手机号脱敏，会员与烟火取自用户所属商户。 */
    public record UserView(Long id, Long tenantId, String tenantName, String name, String phoneMasked, String username,
                           String role, String status, Instant lastLoginAt, Instant createdAt, Instant suspendedAt,
                           boolean memberActive, Instant memberUntil, String planName, long creditsAvailable,
                           boolean operator) {}

    public record UserSummary(long users, long activeLast7Days, long newLast7Days, long suspended) {}

    public record UserSuspensionRequest(
            @jakarta.validation.constraints.NotNull Boolean suspended,
            @jakarta.validation.constraints.Size(max = 500) String note) {}

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
