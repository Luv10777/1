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

    /** Without a status the entry is switched on. */
    public record CreateEntryRequest(
            @NotBlank(message = "问题不能为空") @Size(max = 500) String question,
            @NotBlank(message = "回答不能为空") String answer,
            @Pattern(regexp = "MANUAL|LIVE|DOCUMENT", message = "来源不合法") String source,
            @Pattern(regexp = "ACTIVE|DISABLED", message = "问答状态不合法") String status) {
    }

    public record UpdateEntryRequest(
            @Size(max = 500) String question,
            String answer,
            @Pattern(regexp = "ACTIVE|DISABLED", message = "问答状态不合法") String status) {
    }

    /** Exactly one of storeId and brandId is set. */
    public record SetView(Long id, Long storeId, Long brandId, String name, String kind, String status,
                          String description, Instant createdAt, Instant updatedAt) {
        static SetView of(KnowledgeSet set) {
            return new SetView(set.getId(), set.getStoreId(), set.getBrandId(), set.getName(), set.getKind(),
                    set.getStatus(), set.getDescription(), set.getCreatedAt(), set.getUpdatedAt());
        }
    }

    public record EntryView(Long id, Long knowledgeSetId, Long storeId, Long brandId, String source, String status,
                            String question, String answer, Instant createdAt, Instant updatedAt) {
        static EntryView of(KnowledgeEntry entry) {
            return new EntryView(entry.getId(), entry.getKnowledgeSetId(), entry.getStoreId(), entry.getBrandId(),
                    entry.getSource(), entry.getStatus(), entry.getQuestion(), entry.getAnswer(),
                    entry.getCreatedAt(), entry.getUpdatedAt());
        }
    }

    /**
     * What the next live session at the store will know: switched-on entries of its active FAQ sets,
     * then those of its brand's (entries with a brandId). A store question wins over a brand one.
     */
    public record KnowledgeContext(Long storeId, Long brandId, List<EntryView> entries, Instant generatedAt) {
    }
}
