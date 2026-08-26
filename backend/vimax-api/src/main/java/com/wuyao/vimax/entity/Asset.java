package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资产表
 */
@Entity
@Table(name = "assets")
@Data
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "code", length = 32, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "type", length = 20, nullable = false)
    private String type;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "tags", columnDefinition = "text[]")
    private String tags;

    @Column(name = "file_url", length = 500, nullable = false)
    private String fileUrl;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "duration")
    private Integer duration;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "source", length = 50)
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

    @Column(name = "usage_count")
    private Integer usageCount = 0;

    @Column(name = "status", length = 20)
    private String status = "AVAILABLE";

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
