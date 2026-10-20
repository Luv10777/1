package com.wuyao.growth.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name="billing_plans") @Getter @Setter
public class BillingPlan {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(length=60) private String code;
    @Column(length=100) private String name;
    @Column(length=600) private String description;
    private long priceMinor;
    private int durationDays;
    private long giftCredits;
    private boolean imageEnabled;
    private boolean videoEnabled;
    private boolean liveEnabled;
    private boolean published;
    private Instant createdAt = Instant.now();
    public BillingDtos.PlanSnapshot snapshot() {
        return new BillingDtos.PlanSnapshot(id,code,name,description,priceMinor,durationDays,giftCredits,imageEnabled,videoEnabled,liveEnabled);
    }
}
