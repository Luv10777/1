package com.wuyao.growth.alliance;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 供券单，属于供券商户。门店、商品和主体信息在提交时快照，之后改资料不影响已发出的券。 */
@Entity
@Table(name = "alliance_offers")
@Getter
@Setter
public class AllianceOffer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long productId;
    private String productName;
    private String productCategory;
    private String saleUnit;
    private Long coverAssetId;
    private String storeName;
    private String storeAddress;
    private String storePhone;
    private String storeHours;
    private String supplierName;
    private String supplierCreditCode;
    private long faceValueMinor;
    private int settleRateBp;
    private long settleAmountMinor;
    private int totalQty;
    private int claimedQty;
    private String status = "PENDING_REVIEW";
    private String reviewNote;
    private Long reviewedBy;
    private Instant reviewedAt;
    private Long createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Version
    private Long version;

    int remaining() { return totalQty - claimedQty; }
}
