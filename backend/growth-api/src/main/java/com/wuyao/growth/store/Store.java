package com.wuyao.growth.store;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "stores")
@Getter
@Setter
public class Store {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 120)
    private String name;

    /** 所属品牌。商户还没有建过品牌时为空。 */
    @Column(name = "brand_id")
    private Long brandId;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(length = 300)
    private String address;

    @Column(length = 40)
    private String phone;

    @Column(name = "business_hours", length = 120)
    private String businessHours;

    /** 交通指引、配套服务、特殊营业安排。整体存成一个 JSON 对象。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private StoreDtos.Extras profile = StoreDtos.Extras.EMPTY;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
