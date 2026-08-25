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
@Table(name = "works")
@EntityListeners(AuditingEntityListener.class)
public class Work {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(unique = true, nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkType type;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    @Column(name = "preview_url", length = 500)
    private String previewUrl;

    @Column(name = "content_url", length = 500)
    private String contentUrl;

    @Column(name = "content_text", columnDefinition = "TEXT")
    private String contentText;

    @Column(name = "workflow_id", length = 100)
    private String workflowId;

    @Column(name = "workflow_version", length = 50)
    private String workflowVersion;

    @Column(name = "model_alias", length = 50)
    private String modelAlias;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "generation_cost", precision = 10, scale = 4)
    private BigDecimal generationCost;

    @Column(name = "generation_duration")
    private Integer generationDuration;

    @Column(name = "generation_params", columnDefinition = "jsonb")
    private String generationParams;

    @Column(name = "qa_result", columnDefinition = "jsonb")
    private String qaResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 20, nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.DRAFT;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "platform_content_ids", columnDefinition = "jsonb")
    private String platformContentIds;

    @Column(name = "created_by")
    private Long createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public enum WorkType {
        IMAGE, VIDEO, TEXT, MIXED
    }

    public enum ReviewStatus {
        DRAFT, PENDING, APPROVED, REJECTED, PUBLISHED
    }
}
