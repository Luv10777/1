package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 生成任务表
 */
@Entity
@Table(name = "generation_tasks")
@Data
public class GenerationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "video_project_id", nullable = false)
    private Long videoProjectId;

    @Column(name = "step_number", nullable = false)
    private Integer stepNumber;

    @Column(name = "step_name", length = 100, nullable = false)
    private String stepName;

    @Column(name = "step_type", length = 50, nullable = false)
    private String stepType;

    @Column(name = "input_data", columnDefinition = "JSONB")
    private String inputData;

    @Column(name = "output_data", columnDefinition = "JSONB")
    private String outputData;

    @Column(name = "status", length = 50, nullable = false)
    private String status = "PENDING";

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "max_retries")
    private Integer maxRetries = 3;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "requires_human_review")
    private Boolean requiresHumanReview = false;

    @Column(name = "human_reviewed_at")
    private LocalDateTime humanReviewedAt;

    @Column(name = "human_reviewed_by")
    private Long humanReviewedBy;

    @Column(name = "human_review_result", length = 20)
    private String humanReviewResult;

    @Column(name = "human_review_comment", columnDefinition = "TEXT")
    private String humanReviewComment;
}
