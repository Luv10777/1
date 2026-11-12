package com.wuyao.growth.partner;

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
    /** 服务商的登录账号；role 为 OWNER 或 BD，tenantCount 是归到这个 BD 的商户数（负责人为空）。 */
    public record Member(Long userId, Long uid, String name, String phone, String role, Long tenantCount, Instant createdAt) {}
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
