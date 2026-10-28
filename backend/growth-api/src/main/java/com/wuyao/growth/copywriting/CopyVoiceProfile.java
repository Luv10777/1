package com.wuyao.growth.copywriting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 学我口吻：门店自己发过的文案样本，以及模型总结出的口吻（summary、traits）。 */
@Entity
@Table(name = "copy_voice_profiles")
@Getter
@Setter
public class CopyVoiceProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "store_id", nullable = false) private Long storeId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private List<String> samples = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private Map<String, Object> profile;
    @Column(nullable = false, length = 20) private String status = "EMPTY";
    /** 样本每改一次加一，总结任务只写回同一版样本的结果。 */
    @Column(nullable = false) private int revision;
    @Column(name = "task_id") private Long taskId;
    @Column(name = "update_day") private LocalDate updateDay;
    @Column(name = "update_count", nullable = false) private int updateCount;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
