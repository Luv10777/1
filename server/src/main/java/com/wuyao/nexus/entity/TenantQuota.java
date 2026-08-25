package com.wuyao.nexus.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "tenant_quotas")
@EntityListeners(AuditingEntityListener.class)
public class TenantQuota {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", unique = true, nullable = false)
    private Long tenantId;

    @Column(name = "total_credits", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalCredits = BigDecimal.ZERO;

    @Column(name = "used_credits", precision = 12, scale = 2, nullable = false)
    private BigDecimal usedCredits = BigDecimal.ZERO;

    @Column(name = "reserved_credits", precision = 12, scale = 2, nullable = false)
    private BigDecimal reservedCredits = BigDecimal.ZERO;

    @Column(name = "text_quota_per_day")
    private Integer textQuotaPerDay = 10000;

    @Column(name = "image_quota_per_day")
    private Integer imageQuotaPerDay = 100;

    @Column(name = "video_quota_per_day")
    private Integer videoQuotaPerDay = 10;

    @Column(name = "current_date")
    private LocalDate currentDate = LocalDate.now();

    @Column(name = "text_used_today", nullable = false)
    private Integer textUsedToday = 0;

    @Column(name = "image_used_today", nullable = false)
    private Integer imageUsedToday = 0;

    @Column(name = "video_used_today", nullable = false)
    private Integer videoUsedToday = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public BigDecimal getAvailableCredits() {
        return totalCredits.subtract(usedCredits).subtract(reservedCredits);
    }
}
