package com.wuyao.growth.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 每个用户一个专属邀请码，实名核验通过并同意《推广协议》后生成，之后不变。 */
@Entity
@Table(name = "referral_codes")
@Getter
@Setter
public class ReferralCode {
    @Id @Column(name = "user_id") private Long userId;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(nullable = false, length = 16) private String code;
    @Column(name = "agreement_version", nullable = false, length = 20) private String agreementVersion;
    @Column(name = "agreed_at", nullable = false) private Instant agreedAt = Instant.now();
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
