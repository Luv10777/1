package com.wuyao.growth.partner;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class PartnerCrmDtos {
    private PartnerCrmDtos() {}
    public record Profile(String contactName,String contactPhone,String industry,String region,String stage,
                          Instant nextFollowUpAt,String note,long version,Instant updatedAt) {}
    public record SaveProfile(@Size(max=80) String contactName,@Size(max=40) String contactPhone,@Size(max=80) String industry,
            @Size(max=120) String region,@NotBlank @Pattern(regexp="ACTIVE|AT_RISK|DORMANT") String stage,
            Instant nextFollowUpAt,@Size(max=2000) String note,@Min(0) long version) {}
    public record FollowUp(Long id,String channel,String content,String stage,Instant nextFollowUpAt,Long actorUserId,String actorName,Instant createdAt) {}
    public record NewFollowUp(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
            @NotBlank @Pattern(regexp="PHONE|WECHAT|VISIT|OTHER") String channel,@NotBlank @Size(max=2000) String content,
            @NotBlank @Pattern(regexp="ACTIVE|AT_RISK|DORMANT") String stage,Instant nextFollowUpAt,@Min(0) long version) {}
    public record Crm(Profile profile,PartnerDtos.Page<FollowUp> followUps) {}
    public record Due(Long tenantId,String tenantName,String bdName,Profile profile) {}
}
