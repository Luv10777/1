package com.wuyao.growth.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    public record CreateSetRequest(
            @NotBlank(message = "知识集名称不能为空") @Size(max = 120) String name,
            @Pattern(regexp = "FAQ|DOCUMENT", message = "知识集类型不合法") String kind,
            @Size(max = 500) String description) {
    }

    public record UpdateSetRequest(
            @Size(max = 120) String name,
            @Pattern(regexp = "FAQ|DOCUMENT", message = "知识集类型不合法") String kind,
            @Size(max = 500) String description) {
    }

    public record CreateEntryRequest(
            @NotBlank(message = "问题不能为空") @Size(max = 500) String question,
            @NotBlank(message = "回答不能为空") String answer,
            @Pattern(regexp = "STORE|PRODUCT", message = "关联范围不合法") String scope,
            Long productId,
            @Pattern(regexp = "MANUAL|LIVE_DRAFT|AI_SUGGESTION", message = "来源不合法") String source,
            @Pattern(regexp = "DRAFT|ACTIVE|DISABLED", message = "问答状态不合法") String status) {
    }

    public record UpdateEntryRequest(
            @Size(max = 500) String question,
            String answer,
            @Pattern(regexp = "STORE|PRODUCT", message = "关联范围不合法") String scope,
            Long productId,
            @Pattern(regexp = "MANUAL|LIVE_DRAFT|AI_SUGGESTION", message = "来源不合法") String source,
            @Pattern(regexp = "DRAFT|ACTIVE|DISABLED", message = "问答状态不合法") String status) {
    }

    public record SetView(Long id, Long storeId, String name, String kind, String status,
                          String description, Integer currentVersion, Instant publishedAt,
                          Instant createdAt, Instant updatedAt) {
        static SetView of(KnowledgeSet set) {
            return new SetView(set.getId(), set.getStoreId(), set.getName(), set.getKind(), set.getStatus(),
                    set.getDescription(), set.getCurrentVersion(), set.getPublishedAt(), set.getCreatedAt(),
                    set.getUpdatedAt());
        }
    }

    public record EntryView(Long id, Long knowledgeSetId, Long storeId, Long productId,
                            String scope, String source, String status, String question, String answer,
                            Long hits, Integer manualCorrections, Integer version,
                            Instant createdAt, Instant updatedAt) {
        static EntryView of(KnowledgeEntry entry) {
            return new EntryView(entry.getId(), entry.getKnowledgeSetId(), entry.getStoreId(), entry.getProductId(),
                    entry.getScope(), entry.getSource(), entry.getStatus(), entry.getQuestion(), entry.getAnswer(),
                    entry.getHits(), entry.getManualCorrections(), entry.getVersion(), entry.getCreatedAt(),
                    entry.getUpdatedAt());
        }
    }

    public record KnowledgeContext(Long storeId, Integer version, List<EntryView> entries, Instant generatedAt) {
    }
}
