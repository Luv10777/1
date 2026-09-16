package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 租户额度账户
 */
@Entity
@Table(name = "tenant_credit_accounts")
@Data
public class TenantCreditAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private Long tenantId;

    @Column(name = "total_credits", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalCredits = BigDecimal.ZERO;

    @Column(name = "available_credits", precision = 12, scale = 2, nullable = false)
    private BigDecimal availableCredits = BigDecimal.ZERO;

    @Column(name = "reserved_credits", precision = 12, scale = 2, nullable = false)
    private BigDecimal reservedCredits = BigDecimal.ZERO;

    @Column(name = "used_credits", precision = 12, scale = 2, nullable = false)
    private BigDecimal usedCredits = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
