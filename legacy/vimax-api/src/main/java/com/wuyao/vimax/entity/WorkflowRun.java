package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工作流运行表
 * 对应 SQL: infra/database/004_video_workflow_core.sql
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

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "run_code", length = 50, nullable = false, unique = true)
    private String runCode;

    @Column(name = "workflow_type", length = 50, nullable = false)
    private String workflowType;

    @Column(name = "merchant_fact_snapshot_id")
    private Long merchantFactSnapshotId;

    @Column(name = "state", length = 50, nullable = false)
    private String state = "DRAFT";

    @Column(name = "progress")
    private Integer progress = 0;

    @Column(name = "estimated_cost_credits", precision = 10, scale = 2)
    private BigDecimal estimatedCostCredits;

    @Column(name = "reserved_credits", precision = 10, scale = 2)
    private BigDecimal reservedCredits;

    @Column(name = "actual_cost_credits", precision = 10, scale = 2)
    private BigDecimal actualCostCredits;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
