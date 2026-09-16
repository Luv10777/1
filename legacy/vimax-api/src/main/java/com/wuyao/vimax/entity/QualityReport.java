package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 质量报告表
 */
@Entity
@Table(name = "quality_reports")
@Data
public class QualityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "workflow_run_id", nullable = false)
    private Long workflowRunId;

    @Column(name = "check_type", length = 50, nullable = false)
    private String checkType;

    @Column(name = "check_scope", length = 50, nullable = false)
    private String checkScope;

    @Column(name = "overall_result", length = 20, nullable = false)
    private String overallResult;

    @Column(name = "issues_found")
    private Integer issuesFound = 0;

    @Column(name = "report_data", columnDefinition = "JSONB")
    private String reportData;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;
}
