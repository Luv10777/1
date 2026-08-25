package com.wuyao.nexus.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "brands")
@EntityListeners(AuditingEntityListener.class)
public class Brand {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(unique = true, nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String positioning;

    @Column(name = "target_audience", columnDefinition = "TEXT")
    private String targetAudience;

    @Column(name = "language_style", columnDefinition = "TEXT")
    private String languageStyle;

    @Column(name = "primary_color", length = 20)
    private String primaryColor;

    @Column(name = "logo_assets", columnDefinition = "jsonb")
    private String logoAssets;

    @Column(name = "platform_styles", columnDefinition = "jsonb")
    private String platformStyles;

    @Column(nullable = false)
    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private BrandStatus status = BrandStatus.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public enum BrandStatus {
        ACTIVE, ARCHIVED, DELETED
    }
}
