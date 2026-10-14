package com.wuyao.growth.commerce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "merchant_orders")
@Getter
@Setter
public class SalesOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "store_id", nullable = false)
    private Long storeId;
    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;
    @Column(name = "lookup_token", nullable = false, length = 36)
    private String lookupToken;
    @Column(name = "buyer_name", nullable = false, length = 80)
    private String buyerName;
    @Column(name = "buyer_phone", nullable = false, length = 20)
    private String buyerPhone;
    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;
    @Column(name = "shipping_company", length = 60)
    private String shippingCompany;
    @Column(name = "tracking_number", length = 120)
    private String trackingNumber;
    @Column(name = "buyer_note", length = 500)
    private String buyerNote;
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;
    @Column(nullable = false, length = 24)
    private String status = "PENDING_CONFIRMATION";
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
