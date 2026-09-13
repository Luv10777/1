package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 供应商任务表
 */
@Entity
@Table(name = "provider_jobs")
@Data
public class ProviderJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "generation_task_id", nullable = false)
    private Long generationTaskId;

    @Column(name = "provider", length = 50, nullable = false)
    private String provider;

    @Column(name = "provider_job_id", length = 200, nullable = false)
    private String providerJobId;

    @Column(name = "job_type", length = 50, nullable = false)
    private String jobType;

    @Column(name = "model_capability", length = 50, nullable = false)
    private String modelCapability;

    @Column(name = "provider_model", length = 100)
    private String providerModel;

    @Column(name = "status", length = 50)
    private String status = "SUBMITTED";

    @Column(name = "progress")
    private Integer progress = 0;

    @CreationTimestamp
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "result_url", columnDefinition = "TEXT")
    private String resultUrl;

    @Column(name = "result_asset_id")
    private Long resultAssetId;

    @Column(name = "actual_duration_seconds", precision = 5, scale = 2)
    private BigDecimal actualDurationSeconds;

    @Column(name = "actual_width")
    private Integer actualWidth;

    @Column(name = "actual_height")
    private Integer actualHeight;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "usage_data", columnDefinition = "JSONB")
    private String usageData;

    @Column(name = "estimated_cost", precision = 10, scale = 4)
    private BigDecimal estimatedCost;

    @Column(name = "actual_cost", precision = 10, scale = 4)
    private BigDecimal actualCost;
}
