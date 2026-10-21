package com.wuyao.growth.alliance;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 一个会员期的联盟券额度，属于领券商户。一张会员订单对应一条，期满作废、不累积。 */
@Entity
@Table(name = "alliance_quota_grants")
@Getter
@Setter
public class AllianceQuotaGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private String billingOrderId;
    private String planCode;
    private String planName;
    private long amountMinor;
    private long usedMinor;
    private Instant validFrom;
    private Instant validUntil;
    private Instant createdAt = Instant.now();

    long remainingMinor() { return amountMinor - usedMinor; }
}
