package com.wuyao.nexus.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "assets")
@EntityListeners(AuditingEntityListener.class)
public class Asset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(unique = true, nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetType type;

    @Column(length = 50)
    private String category;

    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    private Integer width;

    private Integer height;

    private Integer duration;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(length = 50)
    private String source;

    @Column(name = "copyright_info", columnDefinition = "TEXT")
    private String copyrightInfo;

    @Column(name = "license_file_url", length = 500)
    private String licenseFileUrl;

    @Column(name = "license_scope", columnDefinition = "TEXT")
    private String licenseScope;

    @Column(name = "license_valid_from")
    private LocalDate licenseValidFrom;

    @Column(name = "license_valid_until")
    private LocalDate licenseValidUntil;

    @Column(name = "usage_count", nullable = false)
    private Integer usageCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AssetStatus status = AssetStatus.AVAILABLE;

    @Column(name = "created_by")
    private Long createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public enum AssetType {
        IMAGE, VIDEO, AUDIO, DOCUMENT
    }

    public enum AssetStatus {
        AVAILABLE, EXPIRED, DISABLED, DELETED
    }
}
