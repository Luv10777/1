package com.wuyao.growth.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 一笔充值给邀请人记一条返利。PENDING 在 availableAt 之前是冻结中，之后可提现。 */
@Entity
@Table(name = "referral_commissions")
@Getter
@Setter
public class ReferralCommission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "referrer_user_id", nullable = false) private Long referrerUserId;
    @Column(name = "invitee_tenant_id", nullable = false) private Long inviteeTenantId;
    @Column(name = "order_id", nullable = false, length = 36) private String orderId;
    @Column(name = "paid_minor", nullable = false) private long paidMinor;
    @Column(name = "rate_bp", nullable = false) private int rateBp;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;
    @Column(nullable = false, length = 16) private String status = "PENDING";
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
