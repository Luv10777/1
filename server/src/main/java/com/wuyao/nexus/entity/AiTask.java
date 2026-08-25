package com.wuyao.nexus.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_tasks")
@EntityListeners(AuditingEntityListener.class)
public class AiTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(unique = true, nullable = false, length = 32)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskType type;

    @Column(name = "model_alias", nullable = false, length = 50)
    private String modelAlias;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "provider_task_id", length = 200)
    private String providerTaskId;

    @Column(name = "input_params", nullable = false, columnDefinition = "jsonb")
    private String inputParams;

    @Column(name = "estimated_cost", precision = 10, scale = 4)
    private BigDecimal estimatedCost;

    @Column(name = "actual_cost", precision = 10, scale = 4)
    private BigDecimal actualCost;

    @Column(name = "estimated_duration")
    private Integer estimatedDuration;

    @Column(name = "actual_duration")
    private Integer actualDuration;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(nullable = false)
    private Integer progress = 0;

    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "callback_status", length = 20)
    private CallbackStatus callbackStatus;

    @Column(name = "callback_attempts", nullable = false)
    private Integer callbackAttempts = 0;

    @Column(nullable = false)
    private Integer priority = 50;

    @Column(name = "created_by")
    private Long createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum TaskType {
        TEXT_GENERATION, IMAGE_GENERATION, VIDEO_GENERATION, BATCH_GENERATION
    }

    public enum TaskStatus {
        PENDING, QUEUED, PROCESSING, COMPLETED, FAILED, CANCELLED, TIMEOUT
    }

    public enum CallbackStatus {
        PENDING, SUCCESS, FAILED
    }
}
