package com.wuyao.growth.copywriting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一次生成：当时的设置和模型写出的 3 条文案，也是「创作记录」里的一条。 */
@Entity
@Table(name = "copy_batches")
@Getter
@Setter
public class CopyBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "store_id", nullable = false) private Long storeId;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "request_key", nullable = false, length = 80) private String requestKey;
    @Column(nullable = false, length = 20) private String platform;
    @Column(nullable = false, length = 20) private String style;
    @Column(name = "store_level", nullable = false, length = 20) private String storeLevel;
    @Column(name = "voice_on", nullable = false) private boolean voiceOn;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_asset_ids", nullable = false, columnDefinition = "jsonb") private List<Long> imageAssetIds = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "work_item_ids", nullable = false, columnDefinition = "jsonb") private List<Long> workItemIds = new ArrayList<>();
    /** 所选商品的 id 和名字，商品后来删了也能在记录里认出来。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private List<CopyDtos.ProductRef> products = new ArrayList<>();
    /** 提交时所选商品的资料快照，生成和改写都以它为准。 */
    @Column(name = "product_facts", columnDefinition = "text") private String productFacts;
    @Column(columnDefinition = "text") private String note;
    @Column(name = "previous_batch_id") private Long previousBatchId;
    @Column(nullable = false, length = 20) private String status = "QUEUED";
    @Column(name = "task_id") private Long taskId;
    @Column(name = "billing_reservation_id", length = 36) private String billingReservationId;
    /** 每条：angle、why、title、body、tags、aiScore、aiReason、revision，改写中的还带 rewriting。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private List<Map<String, Object>> versions;
    @Column(name = "rewrite_count", nullable = false) private int rewriteCount;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public boolean terminal() { return "SUCCEEDED".equals(status) || "FAILED".equals(status); }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
