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

/** 供券商户这边的一条领取记录。只存券码的哈希，核销时按哈希查找。 */
@Entity
@Table(name = "alliance_offer_claims")
@Getter
@Setter
public class AllianceOfferClaim {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private Long offerId;
    private UUID voucherRef;
    private Long holderTenantId;
    private String holderName;
    private String codeHash;
    private Instant expiresAt;
    private String status = "CLAIMED";
    private Long redeemedStoreId;
    private Long redeemedBy;
    private Instant redeemedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
