package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 视频项目表
 * 对应 SQL: infra/database/004_video_workflow_core.sql
 */
@Entity
@Table(name = "video_projects")
@Data
public class VideoProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "project_code", length = 50, nullable = false, unique = true)
    private String projectCode;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "brief", columnDefinition = "TEXT", nullable = false)
    private String brief;

    @Column(name = "target_platform", length = 50)
    private String targetPlatform;

    @Column(name = "aspect_ratio", length = 20)
    private String aspectRatio;

    @Column(name = "target_duration_seconds")
    private Integer targetDurationSeconds;

    @Column(name = "video_count")
    private Integer videoCount = 1;

    @Column(name = "quality_mode", length = 20)
    private String qualityMode = "STANDARD";

    @Column(name = "status", length = 30, nullable = false)
    private String status = "DRAFT";

    @Column(name = "created_by", nullable = false)
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
