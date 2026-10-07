package com.wuyao.growth.live;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity
@Table(name = "live_session_products")
@Getter
@Setter
public class LiveSessionProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
