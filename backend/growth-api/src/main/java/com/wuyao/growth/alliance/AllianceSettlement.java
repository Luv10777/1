package com.wuyao.growth.alliance;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 一次核销对应的结算单，属于供券商户。申诉期内和冻结中不能结算。 */
@Entity
@Table(name = "alliance_settlements")
@Getter
@Setter
public class AllianceSettlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private Long claimId;
    private Long offerId;
    private UUID voucherRef;
    private Long holderTenantId;
    private String productName;
    private long faceValueMinor;
    private long settleAmountMinor;
    private String status = "HOLDING";
    private Instant payableAfter;
    private boolean frozen;
    private String paidReference;
    private Long paidBy;
    private Instant paidAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
