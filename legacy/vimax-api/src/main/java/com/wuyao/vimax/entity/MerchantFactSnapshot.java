package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 商家事实快照
 *
 * Phase 2.1: 用于记录工作流运行时的商家信息
 */
@Entity
@Table(name = "merchant_fact_snapshots")
@Data
public class MerchantFactSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "snapshot_code", length = 50, nullable = false, unique = true)
    private String snapshotCode;

    @Column(name = "snapshot_version", length = 20, nullable = false)
    private String snapshotVersion;

    @Column(name = "merchant_name", length = 200, nullable = false)
    private String merchantName;

    @Column(name = "merchant_type", length = 50)
    private String merchantType;

    @Column(name = "industry", length = 100)
    private String industry;

    @Column(name = "business_hours", length = 200)
    private String businessHours;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "tags", columnDefinition = "TEXT")
    private String tags;

    @Column(name = "product_categories", columnDefinition = "TEXT")
    private String productCategories;

    @Column(name = "key_products", columnDefinition = "TEXT")
    private String keyProducts;

    @Column(name = "selling_points", columnDefinition = "TEXT")
    private String sellingPoints;

    @Column(name = "target_audience", columnDefinition = "TEXT")
    private String targetAudience;

    @Column(name = "brand_voice", columnDefinition = "TEXT")
    private String brandVoice;

    @Column(name = "competitors", columnDefinition = "TEXT")
    private String competitors;

    @Column(name = "marketing_goals", columnDefinition = "TEXT")
    private String marketingGoals;

    @Column(name = "additional_info", columnDefinition = "JSONB")
    private String additionalInfo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
