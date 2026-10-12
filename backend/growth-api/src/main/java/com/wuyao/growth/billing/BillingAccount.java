package com.wuyao.growth.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name="billing_accounts") @Getter @Setter
public class BillingAccount {
    @Id private Long tenantId;
    private long giftAvailable;
    private long purchasedAvailable;
    private long held;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private BillingDtos.PlanSnapshot planSnapshot;
    private Instant memberUntil;
    @Column(length=36) private String membershipOrderId;
    private Instant updatedAt = Instant.now();
    public long available() { return Math.addExact(giftAvailable, purchasedAvailable); }
    public boolean active(Instant now) { return planSnapshot != null && memberUntil != null && memberUntil.isAfter(now); }
}
