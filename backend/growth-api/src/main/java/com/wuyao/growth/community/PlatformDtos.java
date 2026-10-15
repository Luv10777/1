package com.wuyao.growth.community;

import java.time.Instant;
import java.time.LocalDate;

public final class PlatformDtos {
    private PlatformDtos() {}

    public record InformationView(Long id, Long tenantId, String tenantName, String category,
                                  String title, String merchantName, String region, String summary,
                                  String content, String contactName, String contactPhone,
                                  LocalDate validUntil, String status, Instant publishedAt, Instant createdAt) {}

    public record MerchantView(Long id, Long tenantId, String tenantName, String merchantName,
                               String companyName, String industry, String region, String creditCodeMasked,
                               String contactName, String contactPhoneMasked, String introduction,
                               String status, String reviewNote, Instant createdAt, Instant updatedAt) {}

    public record Summary(long publishedInformation, long offlineInformation, long pendingMerchants,
                          long approvedMerchants, long pendingIdentity) {}

    public record MerchantDetailView(Long id, String creditCode, String contactPhone) {}

    public record ReviewRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "PUBLISHED|OFFLINE", message = "信息状态不正确") String status,
            @jakarta.validation.constraints.Size(max = 500) String note) {}

    public record MerchantReviewRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "APPROVED|REJECTED|SUSPENDED", message = "商家状态不正确") String status,
            @jakarta.validation.constraints.Size(max = 500) String note) {}
}
