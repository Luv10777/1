package com.wuyao.growth.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** An uploaded document the text model reads into suggestions; owned by a store or a brand like a set. */
@Entity
@Table(name = "knowledge_documents")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeDocument {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PROCESSING;

    @Column(name = "error_message", length = 300)
    private String errorMessage;

    /** Characters read from the file, before the cut at {@link DocumentText#MAX_CHARS}. */
    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
