package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 商家事实快照表
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

    @Column(name = "snapshot_version", nullable = false, length = 50)
    private String snapshotVersion = "v1.0";

    @Column(name = "snapshot_hash", nullable = false, length = 64, unique = true)
    private String snapshotHash;

    @Column(name = "facts_summary", columnDefinition = "JSONB", nullable = false)
    private String factsSummary;

    @Column(name = "is_complete")
    private Boolean isComplete = false;

    @Column(name = "missing_critical_facts", columnDefinition = "JSONB")
    private String missingCriticalFacts;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
