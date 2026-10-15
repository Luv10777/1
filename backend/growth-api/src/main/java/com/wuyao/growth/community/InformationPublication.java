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
import java.time.LocalDate;

@Entity
@Table(name = "information_publications")
@Getter
@Setter
public class InformationPublication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;
    @Column(nullable = false, length = 24)
    private String category;
    @Column(nullable = false, length = 60)
    private String title;
    @Column(name = "merchant_name", nullable = false, length = 80)
    private String merchantName;
    @Column(nullable = false, length = 80)
    private String region;
    @Column(length = 120)
    private String summary;
    @Column(nullable = false, length = 5000)
    private String content;
    @Column(name = "contact_name", length = 40)
    private String contactName;
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(nullable = false, length = 20)
    private String status = "PUBLISHED";
    @Column(name = "platform_blocked", nullable = false)
    private boolean platformBlocked = false;
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt = Instant.now();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
