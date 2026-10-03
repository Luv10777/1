package com.wuyao.growth.video;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "video_provider_jobs")
@Getter
@Setter
public class VideoProviderJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "workflow_id", nullable = false) private Long workflowId;
    @Column(nullable = false, length = 80) private String provider;
    @Column(nullable = false, length = 40) private String model;
    @Column(name = "provider_job_id", nullable = false, length = 200) private String providerJobId;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "poll_round", nullable = false) private int pollRound;
    @Column(name = "result_url", columnDefinition = "text") private String resultUrl;
    @Column(name = "result_expires_at") private Instant resultExpiresAt;
    @Column(name = "last_error_code", length = 64) private String lastErrorCode;
    @Column(name = "last_error_message", columnDefinition = "text") private String lastErrorMessage;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
}
