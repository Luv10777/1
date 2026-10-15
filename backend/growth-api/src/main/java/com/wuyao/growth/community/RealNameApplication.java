package com.wuyao.growth.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "real_name_applications")
@Getter
@Setter
public class RealNameApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "legal_name_ciphertext", nullable = false, columnDefinition = "bytea")
    private byte[] legalNameCiphertext;
    @Column(name = "id_number_ciphertext", nullable = false, columnDefinition = "bytea")
    private byte[] idNumberCiphertext;
    @Column(name = "legal_name_masked", nullable = false, length = 80)
    private String legalNameMasked;
    @Column(name = "id_number_masked", nullable = false, length = 32)
    private String idNumberMasked;
    @Column(nullable = false, length = 24)
    private String status = "PENDING_REVIEW";
    @Column(name = "review_note", length = 500)
    private String reviewNote;
    @Column(name = "reviewed_by")
    private Long reviewedBy;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
