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

/** A store-wide FAQ entry; product questions live with the product. Documents will use a separate entry model later. */
@Entity
@Table(name = "knowledge_entries")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeEntry {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_LIVE = "LIVE";
    public static final String SOURCE_DOCUMENT = "DOCUMENT";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Mirrors the set's owner: exactly one of storeId and brandId is set. */
    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "knowledge_set_id", nullable = false)
    private Long knowledgeSetId;

    @Column(nullable = false, length = 30)
    private String source = SOURCE_MANUAL;

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
