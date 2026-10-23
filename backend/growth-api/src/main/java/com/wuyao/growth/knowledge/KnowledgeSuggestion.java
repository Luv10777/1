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

/** One thing a document suggests: a new wording for a brand or store field, or a question and answer. */
@Entity
@Table(name = "knowledge_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeSuggestion {

    public static final String KIND_FIELD = "FIELD";
    public static final String KIND_QA = "QA";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_DISMISSED = "DISMISSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(length = 40)
    private String field;

    @Column(columnDefinition = "text")
    private String value;

    @Column(length = 500)
    private String question;

    @Column(columnDefinition = "text")
    private String answer;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
