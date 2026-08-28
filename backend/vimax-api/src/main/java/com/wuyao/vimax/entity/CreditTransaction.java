package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 额度交易记录
 */
@Entity
@Table(name = "credit_transactions")
@Data
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "transaction_type", length = 20, nullable = false)
    private String transactionType; // RESERVE, RELEASE, CONSUME, RECHARGE

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "reference_type", length = 50)
    private String referenceType; // WORKFLOW_RUN, GENERATION_TASK, PAYMENT

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "transaction_time", nullable = false)
    private LocalDateTime transactionTime;
}
