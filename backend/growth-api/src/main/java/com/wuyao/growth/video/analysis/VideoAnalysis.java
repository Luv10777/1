package com.wuyao.growth.video.analysis;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "video_analyses")
@Getter
@Setter
public class VideoAnalysis {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "request_key", nullable = false, length = 80) private String requestKey;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 8) private String mode;
    @Column(name = "reverse_need", columnDefinition = "text") private String reverseNeed;
    @Column(name = "asset_id", nullable = false) private Long assetId;
    @Column(name = "source_url", columnDefinition = "text") private String sourceUrl;
    @Column(nullable = false, length = 20) private String status = "QUEUED";
    @Column(nullable = false) private int progress = 0;
    @Column(name = "task_id") private Long taskId;
    @Column(name = "duration_ms") private Integer durationMs;
    private Integer width;
    private Integer height;
    @Column(name = "has_audio") private Boolean hasAudio;
    @Column(name = "audio_storage_key", columnDefinition = "text") private String audioStorageKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audio_analysis", columnDefinition = "jsonb") private Map<String, Object> audioAnalysis;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private List<Frame> frames = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private Map<String, Object> result;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }
    public boolean terminal() { return "SUCCEEDED".equals(status) || "FAILED".equals(status); }
    public record Frame(double seconds, String storageKey) {}
}
