package com.wuyao.growth.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="billing_reservations") @Getter @Setter
public class BillingReservation {
    private Long createdBy;
    @Id @Column(length=36) private String id = UUID.randomUUID().toString();
    private Long tenantId;
    @Column(length=120) private String requestKey;
    @Column(length=20) private String capability;
    private long giftHeld;
    private long purchasedHeld;
    private long captured;
    private long initialAmount;
    private Instant createdAt = Instant.now();
    public long remaining() { return Math.addExact(giftHeld,purchasedHeld); }
}
