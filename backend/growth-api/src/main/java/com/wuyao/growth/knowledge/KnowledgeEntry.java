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

/** A structured FAQ entry. Documents will use a separate entry model later. */
@Entity
@Table(name = "knowledge_entries")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeEntry {

    public static final String SCOPE_STORE = "STORE";
    public static final String SCOPE_PRODUCT = "PRODUCT";

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_LIVE = "LIVE_DRAFT";
    public static final String SOURCE_AI = "AI_SUGGESTION";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "knowledge_set_id", nullable = false)
    private Long knowledgeSetId;

    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false, length = 20)
    private String scope = SCOPE_STORE;

    @Column(nullable = false, length = 30)
    private String source = SOURCE_MANUAL;

    @Column(nullable = false, length = 20)
    private String status = STATUS_DRAFT;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    @Column(nullable = false)
    private Long hits = 0L;

    @Column(name = "manual_corrections", nullable = false)
    private Integer manualCorrections = 0;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
