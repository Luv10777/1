package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 工作流步骤
 *
 * Phase 2.2: 工作流的每个执行步骤
 */
@Entity
@Table(name = "workflow_steps")
@Data
public class WorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "workflow_run_id", nullable = false)
    private Long workflowRunId;

    @Column(name = "step_code", length = 50, nullable = false)
    private String stepCode;

    @Column(name = "step_name", length = 100, nullable = false)
    private String stepName;

    @Column(name = "step_type", length = 50, nullable = false)
    private String stepType;

    @Column(name = "sequence_order", nullable = false)
    private Integer sequenceOrder;

    @Column(name = "depends_on_step_id")
    private Long dependsOnStepId;

    @Column(name = "state", length = 50, nullable = false)
    private String state = "PENDING";

    @Column(name = "input_spec", columnDefinition = "JSONB")
    private String inputSpec;

    @Column(name = "output_data", columnDefinition = "JSONB")
    private String outputData;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "max_retries")
    private Integer maxRetries = 3;

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

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
