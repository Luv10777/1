package com.wuyao.growth.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 商品事实。商品图片和商品问答分别通过 product_images/product_faqs 关联。 */
@Entity
@Table(name = "products")
@Getter
@Setter
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(nullable = false, length = 200)
    private String name;

    /** PHYSICAL / VOUCHER。API 层会把中文展示值归一化为这两个值。 */
    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "sale_unit", nullable = false, length = 40)
    private String saleUnit;

    @Column(length = 500)
    private String specification;

    @Column(name = "promotion_rule", length = 1000)
    private String promotionRule;

    @Column(name = "core_selling_points", length = 2000)
    private String coreSellingPoints;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
