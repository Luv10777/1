package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生成任务表
 * 对应 SQL: infra/database/004_video_workflow_core.sql
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

    @Column(name = "workflow_run_id", nullable = false)
    private Long workflowRunId;

    @Column(name = "step_id")
    private Long stepId;

    @Column(name = "idempotency_key", length = 100, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "task_type", length = 50, nullable = false)
    private String taskType;

    @Column(name = "model_capability", length = 50, nullable = false)
    private String modelCapability;

    @Column(name = "input_hash", length = 64, nullable = false)
    private String inputHash;

    @Column(name = "status", length = 50)
    private String status = "PENDING";

    @Column(name = "provider_request_id", length = 200)
    private String providerRequestId;

    @Column(name = "provider_job_id", length = 200)
    private String providerJobId;

    @Column(name = "result_ref", length = 500)
    private String resultRef;

    @Column(name = "estimated_cost", precision = 10, scale = 4)
    private BigDecimal estimatedCost;

    @Column(name = "actual_cost", precision = 10, scale = 4)
    private BigDecimal actualCost;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
