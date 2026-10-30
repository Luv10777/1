package com.wuyao.growth.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 被邀请商户这边的记录：我的邀请人是谁。只在注册时写一次，没有这条就归属公司。 */
@Entity
@Table(name = "referral_attributions")
@Getter
@Setter
public class ReferralAttribution {
    @Id @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "referrer_user_id", nullable = false) private Long referrerUserId;
    @Column(name = "referrer_tenant_id", nullable = false) private Long referrerTenantId;
    @Column(nullable = false, length = 16) private String code;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
