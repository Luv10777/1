package com.wuyao.growth.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "merchant_applications")
@Getter
@Setter
public class MerchantApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;
    @Column(name = "merchant_name", nullable = false, length = 80)
    private String merchantName;
    @Column(name = "company_name", nullable = false, length = 120)
    private String companyName;
    @Column(nullable = false, length = 80)
    private String industry;
    @Column(nullable = false, length = 120)
    private String region;
    @Column(name = "credit_code", nullable = false, length = 18)
    private String creditCode;
    @Column(name = "contact_name", nullable = false, length = 40)
    private String contactName;
    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;
    @Column(nullable = false, length = 1000)
    private String introduction;
    @Column(nullable = false, length = 24)
    private String status = "PENDING_REVIEW";
    @Column(name = "review_note", length = 500)
    private String reviewNote;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
