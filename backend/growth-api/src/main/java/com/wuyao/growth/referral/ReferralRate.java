package com.wuyao.growth.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 平台返利比例的一次调整。按 kind（TOPUP 充值 / MEMBERSHIP 开通会员）分开，只增不改，各自最新一行生效。 */
@Entity
@Table(name = "referral_rates")
@Getter
@Setter
public class ReferralRate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 16) private String kind;
    @Column(name = "rate_bp", nullable = false) private int rateBp;
    @Column(nullable = false, length = 200) private String note;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
