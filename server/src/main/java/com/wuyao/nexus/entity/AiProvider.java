package com.wuyao.nexus.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_providers")
@EntityListeners(AuditingEntityListener.class)
public class AiProvider {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderType type;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute = 60;

    @Column(name = "rate_limit_per_day")
    private Integer rateLimitPerDay = 10000;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 300;

    @Column(name = "retry_times")
    private Integer retryTimes = 3;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private ProviderStatus status = ProviderStatus.ACTIVE;

    @Column(columnDefinition = "jsonb")
    private String config;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ProviderType {
        TEXT, IMAGE, VIDEO, AUDIO, MULTIMODAL
    }

    public enum ProviderStatus {
        ACTIVE, DISABLED, MAINTENANCE
    }
}
