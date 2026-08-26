package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 视频项目表
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

    @Column(name = "project_code", length = 64, nullable = false, unique = true)
    private String projectCode;

    @Column(name = "project_name", length = 200, nullable = false)
    private String projectName;

    @Column(name = "project_type", length = 50)
    private String projectType;

    @Column(name = "merchant_snapshot_id")
    private Long merchantSnapshotId;

    @Column(name = "user_input", columnDefinition = "TEXT")
    private String userInput;

    @Column(name = "requirements", columnDefinition = "JSONB")
    private String requirements;

    @Column(name = "status", length = 50, nullable = false)
    private String status = "DRAFT";

    @Column(name = "current_step")
    private Integer currentStep;

    @Column(name = "progress")
    private Integer progress = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
