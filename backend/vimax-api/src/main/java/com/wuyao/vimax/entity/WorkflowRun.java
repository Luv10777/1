package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 工作流运行表
 */
@Entity
@Table(name = "workflow_runs")
@Data
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "video_project_id", nullable = false)
    private Long videoProjectId;

    @Column(name = "run_id", length = 50, nullable = false, unique = true)
    private String runId;

    @Column(name = "status", length = 50, nullable = false)
    private String status = "RUNNING";

    @Column(name = "current_step_name", length = 100)
    private String currentStepName;

    @Column(name = "paused_for_human_review")
    private Boolean pausedForHumanReview = false;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;
}
