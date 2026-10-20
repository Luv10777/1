package com.wuyao.growth.alliance;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 领券商户手里的一张联盟券。券码明文只存在这里，只给本商户看。 */
@Entity
@Table(name = "alliance_vouchers")
@Getter
@Setter
public class AllianceVoucher {
    @Id
    private UUID voucherRef;
    private Long tenantId;
    private Long quotaGrantId;
    private Long offerId;
    private Long supplierTenantId;
    private Long storeId;
    private String storeName;
    private String storeAddress;
    private String storePhone;
    private String storeHours;
    private String productName;
    private String saleUnit;
    private Long coverAssetId;
    private long faceValueMinor;
    private String code;
    private String status = "CLAIMING";
    private Long claimedBy;
    private Instant claimedAt;
    private Instant expiresAt;
    private Instant redeemedAt;
    private String disputeReason;
    private Instant disputedAt;
    private String disputeResult;
    private String disputeNote;
    private Instant disputeResolvedAt;
    private Instant updatedAt = Instant.now();
}
