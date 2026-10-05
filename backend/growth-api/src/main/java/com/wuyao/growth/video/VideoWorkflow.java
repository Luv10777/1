package com.wuyao.growth.video;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "video_workflows")
@Getter
@Setter
public class VideoWorkflow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "request_key", nullable = false, length = 80) private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private VideoDtos.Create request;
    @Column(nullable = false, length = 40) private String model;
    @Column(nullable = false, length = 8) private String ratio;
    @Column(name = "duration_seconds", nullable = false) private int durationSeconds;
    @Column(nullable = false, length = 8) private String resolution;
    @Column(nullable = false, length = 32) private String status = "QUEUED";
    @Column(nullable = false, length = 32) private String stage = "SUBMIT";
    @Column(nullable = false) private int progress;
    @Column(name = "task_id") private Long taskId;
    @Column(name = "provider_job_id", length = 200) private String providerJobId;
    @Column(name = "provider_status", length = 32) private String providerStatus;
    @Column(name = "provider_result_url", columnDefinition = "text") private String providerResultUrl;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "provider_submit_request", columnDefinition = "jsonb")
    private Map<String, Object> providerSubmitRequest;
    @Column(name = "poll_round", nullable = false) private int pollRound;
    @Column(name = "video_concurrency_permit_held", nullable = false) private boolean videoConcurrencyPermitHeld;
    @Column(name = "output_asset_id") private Long outputAssetId;
    @Column(name = "output_storage_key", length = 500) private String outputStorageKey;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
