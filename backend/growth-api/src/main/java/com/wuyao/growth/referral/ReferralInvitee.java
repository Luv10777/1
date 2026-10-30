package com.wuyao.growth.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 邀请人这边看到的被邀请商户，只有打码后的手机号。 */
@Entity
@Table(name = "referral_invitees")
@Getter
@Setter
public class ReferralInvitee {
    @Id @Column(name = "invitee_tenant_id") private Long inviteeTenantId;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "referrer_user_id", nullable = false) private Long referrerUserId;
    @Column(name = "invitee_phone_masked", nullable = false, length = 20) private String inviteePhoneMasked;
    @Column(name = "registered_at", nullable = false) private Instant registeredAt = Instant.now();
}
