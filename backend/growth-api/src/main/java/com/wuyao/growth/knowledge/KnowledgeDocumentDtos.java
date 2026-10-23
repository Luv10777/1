package com.wuyao.growth.knowledge;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class KnowledgeDocumentDtos {

    private KnowledgeDocumentDtos() {
    }

    public record CreateRequest(@NotNull(message = "请先上传文档") @Positive Long assetId) {
    }

    /**
     * What the merchant settled on: an edited value for a field, an edited question and answer and the
     * set to put them in for a QA. Left out, the suggestion is taken as written.
     */
    public record AcceptRequest(String value,
                                @Size(max = 500) String question,
                                String answer,
                                @Positive Long knowledgeSetId) {
    }

    /** truncated: the document was longer than what the model read. */
    public record DocumentView(Long id, Long storeId, Long brandId, String name, String status, String errorMessage,
                               Integer charCount, boolean truncated, long pendingCount,
                               Instant createdAt, Instant updatedAt) {
        static DocumentView of(KnowledgeDocument document, long pendingCount) {
            return new DocumentView(document.getId(), document.getStoreId(), document.getBrandId(), document.getName(),
                    document.getStatus(), document.getErrorMessage(), document.getCharCount(),
                    document.getCharCount() != null && document.getCharCount() > DocumentText.MAX_CHARS, pendingCount,
                    document.getCreatedAt(), document.getUpdatedAt());
        }
    }

    /** For a FIELD: its label, what it says now and what the document suggests. For a QA: the pair. */
    public record SuggestionView(Long id, String kind, String field, String fieldLabel, String currentValue,
                                 String value, String question, String answer, String status) {
    }

    public record DocumentDetail(DocumentView document, List<SuggestionView> suggestions) {
    }
}
