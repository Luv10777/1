package com.wuyao.growth.creative.inspiration;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 灵感绘图出的一批灵感方案：3 个画面描述。 */
@Entity
@Table(name = "image_inspirations")
@Getter
@Setter
public class ImageInspiration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "request_key", nullable = false, length = 80) private String requestKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_asset_ids", nullable = false, columnDefinition = "jsonb") private List<Long> imageAssetIds = new ArrayList<>();
    /** 左侧选的绘图风格，未选为空。 */
    @Column(length = 40) private String style;
    @Column(columnDefinition = "text") private String idea;
    /** 「换一批」时指向上一批。 */
    @Column(name = "previous_id") private Long previousId;
    @Column(nullable = false, length = 20) private String status = "QUEUED";
    @Column(name = "task_id") private Long taskId;
    @Column(name = "billing_reservation_id", length = 36) private String billingReservationId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private List<Map<String, Object>> plans;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public boolean terminal() { return "SUCCEEDED".equals(status) || "FAILED".equals(status); }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
