package com.wuyao.growth.community;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class RealNameDtos {
    private RealNameDtos() {}

    public record ApplicationRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[\\p{IsHan}·]{2,40}", message = "请输入真实姓名") String legalName,
            @NotBlank @Pattern(regexp = "[1-9]\\d{16}[0-9Xx]", message = "请输入18位居民身份证号码") String idNumber,
            @NotNull @AssertTrue(message = "请确认身份信息真实、准确") Boolean declarationAccepted) {}

    public record ApplicationView(Long id, String legalNameMasked, String idNumberMasked,
                                  String status, String reviewNote, Instant submittedAt, Instant reviewedAt) {}

    public record AdminView(Long id, Long tenantId, String tenantName, Long userId, String userName,
                            String phoneMasked, String legalNameMasked, String idNumberMasked,
                            String status, String reviewNote, Instant submittedAt, Instant reviewedAt) {}

    public record DetailView(Long id, Long tenantId, String tenantName, Long userId, String userName,
                             String phoneMasked, String legalName, String idNumber,
                             String legalNameMasked, String idNumberMasked, String status,
                             String reviewNote, Instant submittedAt, Instant reviewedAt) {}
}
