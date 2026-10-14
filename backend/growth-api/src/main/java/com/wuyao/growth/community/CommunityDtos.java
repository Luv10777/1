package com.wuyao.growth.community;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class CommunityDtos {
    private CommunityDtos() {}

    public record ApplicationRequest(
            @NotBlank @Size(max = 80) String merchantName,
            @NotBlank @Size(max = 120) String companyName,
            @NotBlank @Size(max = 80) String industry,
            @NotBlank @Size(max = 120) String region,
            @NotBlank @Pattern(regexp = "[0-9ABCDEFGHJKLMNPQRTUWXY]{18}") String creditCode,
            @NotBlank @Size(max = 40) String contactName,
            @NotBlank @Pattern(regexp = "[0-9+()\\- ]{6,20}") String contactPhone,
            @NotBlank @Size(max = 1000) String introduction,
            @NotNull @AssertTrue(message = "请确认申请资料真实并获得授权") Boolean declarationAccepted) {}

    public record ApplicationView(Long id, String merchantName, String companyName, String industry,
                                  String region, String creditCode, String contactName, String contactPhone,
                                  String introduction, String status, String reviewNote,
                                  Instant createdAt, Instant updatedAt) {
        static ApplicationView of(MerchantApplication value) {
            return new ApplicationView(value.getId(), value.getMerchantName(), value.getCompanyName(),
                    value.getIndustry(), value.getRegion(), value.getCreditCode(), value.getContactName(),
                    value.getContactPhone(), value.getIntroduction(), value.getStatus(), value.getReviewNote(),
                    value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record InformationRequest(
            @NotBlank @Pattern(regexp = "activity|cooperation|supply|notice") String category,
            @NotBlank @Size(max = 60) String title,
            @NotBlank @Size(max = 80) String merchantName,
            @NotBlank @Size(max = 80) String region,
            @Size(max = 120) String summary,
            @NotBlank @Size(max = 5000) String content,
            @Size(max = 40) String contactName,
            @Pattern(regexp = "^$|[0-9+()\\- ]{6,20}") String contactPhone,
            LocalDate validUntil,
            @NotNull @AssertTrue(message = "请确认发布内容真实并接受平台审核") Boolean declarationAccepted) {}

    public record InformationView(Long id, String category, String title, String merchantName, String region,
                                  String summary, String content, String contactName, String contactPhone,
                                  LocalDate validUntil, String status, Instant publishedAt, Instant createdAt) {
        static InformationView of(InformationPublication value) {
            return new InformationView(value.getId(), value.getCategory(), value.getTitle(), value.getMerchantName(),
                    value.getRegion(), value.getSummary(), value.getContent(), value.getContactName(),
                    value.getContactPhone(), value.getValidUntil(), value.getStatus(), value.getPublishedAt(), value.getCreatedAt());
        }
    }
}
