package com.wuyao.vimax.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 供应商配置表
 */
@Entity
@Table(name = "provider_configs")
@Data
public class ProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "provider_type", length = 50, nullable = false)
    private String providerType;  // FLUAPI, TOAPIS

    @Column(name = "config_name", length = 100, nullable = false)
    private String configName;

    @Column(name = "api_endpoint", length = 500, nullable = false)
    private String apiEndpoint;

    @Column(name = "api_key_encrypted", columnDefinition = "TEXT", nullable = false)
    private String apiKeyEncrypted;

    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    @Column(name = "rate_limit_per_day")
    private Integer rateLimitPerDay;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 30;

    @Column(name = "retry_count")
    private Integer retryCount = 3;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "priority")
    private Integer priority = 100;

    @Column(name = "extra_config", columnDefinition = "JSONB")
    private String extraConfig;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;
}
