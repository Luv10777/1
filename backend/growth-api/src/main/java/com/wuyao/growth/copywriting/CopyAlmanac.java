package com.wuyao.growth.copywriting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 一家门店某一天的「今日宜发」：黄历的宜、忌、时令短句，3 个选题，以及给门店志的一句提醒。 */
@Entity
@Table(name = "copy_almanacs")
@Getter
@Setter
public class CopyAlmanac {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "store_id", nullable = false) private Long storeId;
    @Column(nullable = false) private LocalDate day;
    /** 品牌库的行业选项；判断不了时是「通用」。 */
    @Column(nullable = false, length = 60) private String industry;
    @Column(nullable = false, length = 20) private String status = "GENERATING";
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> facts;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private List<String> good;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private List<String> avoid;
    @Column(columnDefinition = "text") private String quote;
    /** 每个选题：title、platform、style、idea。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private List<Map<String, Object>> topics;
    @Column(name = "journal_hint", columnDefinition = "text") private String journalHint;
    @Column(name = "task_id") private Long taskId;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
