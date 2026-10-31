package com.wuyao.growth.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 一张提现单。amount = commission（带走的可提现返利）- clawback（扣回的已作废返利）。
 * 收款卡在申请时抄下；打款时财务填代扣税额，实发 net = amount - tax。
 */
@Entity
@Table(name = "referral_withdrawals")
@Getter
@Setter
public class ReferralWithdrawal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "commission_minor", nullable = false) private long commissionMinor;
    @Column(name = "clawback_minor", nullable = false) private long clawbackMinor;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;
    /** PENDING 待审核 / REJECTED 已驳回 / PAID 已打款。 */
    @Column(nullable = false, length = 16) private String status = "PENDING";
    @Column(name = "bank_name", nullable = false, length = 40) private String bankName;
    @Column(name = "card_number_ciphertext", nullable = false, columnDefinition = "bytea") private byte[] cardNumberCiphertext;
    @Column(name = "card_last4", nullable = false, length = 4) private String cardLast4;
    /** 申请时同意的《推广协议》版本。 */
    @Column(name = "agreement_version", nullable = false, length = 20) private String agreementVersion;
    @Column(name = "review_note", length = 200) private String reviewNote;
    @Column(name = "reviewed_by") private Long reviewedBy;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "tax_minor") private Long taxMinor;
    @Column(name = "net_minor") private Long netMinor;
    @Column(name = "paid_reference", length = 100) private String paidReference;
    @Column(name = "paid_date") private LocalDate paidDate;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
