package com.wuyao.growth.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="billing_orders") @Getter @Setter
public class BillingOrder {
    @Id @Column(length=36) private String id = UUID.randomUUID().toString();
    private Long tenantId;
    @Column(length=80) private String requestKey;
    @Column(length=20) private String kind;
    private long amountMinor;
    private long credits;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private BillingDtos.PlanSnapshot planSnapshot;
    @Column(length=24) private String state = "PENDING_PAYMENT";
    @Column(length=16) private String paymentMethod;
    @Column(columnDefinition="text") private String paymentCodeUrl;
    @Column(length=160) private String receiptReference;
    private Long confirmedBy;
    private Long createdBy;
    private Instant reconciliationAfter;
    private Instant paidAt;
    private Instant expiresAt;
    private Instant createdAt = Instant.now();
}
