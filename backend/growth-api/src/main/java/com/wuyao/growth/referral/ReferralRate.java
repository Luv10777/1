package com.wuyao.growth.referral;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 平台返利比例的一次调整。只增不改，最新一行生效。 */
@Entity
@Table(name = "referral_rates")
@Getter
@Setter
public class ReferralRate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "rate_bp", nullable = false) private int rateBp;
    @Column(nullable = false, length = 200) private String note;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
