package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 商家事实表
 */
@Entity
@Table(name = "merchant_facts")
@Data
public class MerchantFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "fact_type", nullable = false, length = 50)
    private String factType;

    @Column(name = "fact_key", nullable = false, length = 100)
    private String factKey;

    @Column(name = "fact_value", columnDefinition = "JSONB", nullable = false)
    private String factValue;

    @Column(name = "is_critical")
    private Boolean isCritical = false;

    @Column(name = "source", length = 100)
    private String source;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "status", length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
