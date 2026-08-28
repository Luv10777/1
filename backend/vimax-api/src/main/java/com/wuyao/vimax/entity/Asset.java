package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资产表 - 统一管理所有文件
 * 对应 SQL: infra/database/005_video_workflow_support.sql
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

    @Column(name = "asset_type", length = 50, nullable = false)
    private String assetType;

    @Column(name = "asset_category", length = 50)
    private String assetCategory;

    @Column(name = "s3_bucket", length = 100, nullable = false)
    private String s3Bucket;

    @Column(name = "s3_key", length = 500, nullable = false)
    private String s3Key;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "duration_seconds", precision = 5, scale = 2)
    private BigDecimal durationSeconds;

    @Column(name = "sha256_hash", length = 64)
    private String sha256Hash;

    @Column(name = "metadata", columnDefinition = "JSONB")
    private String metadata;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "source", length = 100)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
