package com.wuyao.growth.copywriting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 门店志：定稿的那一条，门店占位已换成真实值，就是发出去的样子。 */
@Entity
@Table(name = "copy_journal_entries")
@Getter
@Setter
public class CopyJournalEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "store_id", nullable = false) private Long storeId;
    @Column(name = "batch_id", nullable = false) private Long batchId;
    @Column(name = "version_index", nullable = false) private int versionIndex;
    @Column(nullable = false, length = 20) private String platform;
    @Column(columnDefinition = "text") private String title;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private List<String> tags = new ArrayList<>();
    @Column(name = "finalized_by") private Long finalizedBy;
    @Column(name = "finalized_at", nullable = false, updatable = false) private Instant finalizedAt = Instant.now();
}
