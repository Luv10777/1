package com.wuyao.nexus.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "model_aliases")
@EntityListeners(AuditingEntityListener.class)
public class ModelAlias {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String alias;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "provider_model_name", nullable = false, length = 100)
    private String providerModelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModelType type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cost_per_1k_tokens", precision = 10, scale = 6)
    private BigDecimal costPer1kTokens;

    @Column(name = "cost_per_image", precision = 10, scale = 4)
    private BigDecimal costPerImage;

    @Column(name = "cost_per_video_second", precision = 10, scale = 4)
    private BigDecimal costPerVideoSecond;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "max_resolution", length = 20)
    private String maxResolution;

    @Column(name = "max_duration")
    private Integer maxDuration;

    @Column(nullable = false)
    private Integer priority = 100;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private ModelStatus status = ModelStatus.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ModelType {
        TEXT, IMAGE, VIDEO, AUDIO
    }

    public enum ModelStatus {
        ACTIVE, DEPRECATED, DISABLED
    }
}
