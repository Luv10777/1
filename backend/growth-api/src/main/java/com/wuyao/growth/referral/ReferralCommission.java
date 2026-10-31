package com.wuyao.growth.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 一笔充值或会员订单给邀请人记一条返利。PENDING 在 availableAt 之前是冻结中，之后可提现；
 * 被提现单带走后 withdrawalId 有值，打款后记 withdrawnAt。已打款的返利作废后由下一张提现单扣回（clawbackWithdrawalId）。
 */
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
    /** TOPUP 充值烟火 / MEMBERSHIP 开通会员。 */
    @Column(name = "order_kind", nullable = false, length = 16) private String orderKind;
    @Column(name = "paid_minor", nullable = false) private long paidMinor;
    @Column(name = "rate_bp", nullable = false) private int rateBp;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;
    @Column(nullable = false, length = 16) private String status = "PENDING";
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "withdrawal_id") private Long withdrawalId;
    @Column(name = "withdrawn_at") private Instant withdrawnAt;
    @Column(name = "clawback_withdrawal_id") private Long clawbackWithdrawalId;
    @Column(name = "void_note", length = 200) private String voidNote;
    @Column(name = "voided_by") private Long voidedBy;
    @Column(name = "voided_at") private Instant voidedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
