package com.wuyao.nexus.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "knowledge")
@EntityListeners(AuditingEntityListener.class)
public class Knowledge {
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
    private KnowledgeType type;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", length = 20, nullable = false)
    private ProcessStatus parseStatus = ProcessStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_status", length = 20, nullable = false)
    private ProcessStatus ocrStatus = ProcessStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "vector_status", length = 20, nullable = false)
    private ProcessStatus vectorStatus = ProcessStatus.PENDING;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    @Column(name = "structured_data", columnDefinition = "jsonb")
    private String structuredData;

    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(nullable = false)
    private Boolean verified = false;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private KnowledgeStatus status = KnowledgeStatus.DRAFT;

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

    public enum KnowledgeType {
        FILE, TEXT, URL, STRUCTURED
    }

    public enum ProcessStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }

    public enum KnowledgeStatus {
        DRAFT, PUBLISHED, EXPIRED, DELETED
    }
}
