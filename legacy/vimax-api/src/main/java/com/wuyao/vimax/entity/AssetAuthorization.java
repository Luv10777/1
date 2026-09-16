package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 资产授权表
 */
@Entity
@Table(name = "asset_authorizations")
@Data
public class AssetAuthorization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "authorization_scope", length = 50, nullable = false)
    private String authorizationScope;

    @Column(name = "scope_reference", length = 200)
    private String scopeReference;

    @Column(name = "authorized_by")
    private Long authorizedBy;

    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "status", length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
