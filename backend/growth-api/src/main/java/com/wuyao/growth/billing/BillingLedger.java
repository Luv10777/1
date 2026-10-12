package com.wuyao.growth.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

@Entity @Table(name="billing_ledger") @Immutable @Getter @Setter
public class BillingLedger {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long tenantId;
    @Column(length=160) private String eventKey;
    @Column(length=24) private String type;
    @Column(length=240) private String description;
    private long giftDelta;
    private long purchasedDelta;
    private long heldDelta;
    private long availableAfter;
    private long heldAfter;
    @Column(length=36) private String orderId;
    @Column(length=36) private String reservationId;
    private Instant createdAt = Instant.now();
}
