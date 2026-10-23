package com.wuyao.growth.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderRequest;
import com.wuyao.growth.common.gateway.ProviderResult;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads an uploaded document and asks the text model what it says about the brand or store, saving the
 * answer as suggestions for the merchant to accept or dismiss. Every way it can go wrong ends in FAILED with
 * a reason the merchant can act on; nothing is retried behind their back, since each call costs model time.
 * The model call's idempotency key is the task's, so a reclaimed task does not count as a new request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentDigestHandler implements TaskHandler {

    public static final String TYPE = "KNOWLEDGE_DOCUMENT_DIGEST";

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeSuggestionRepository suggestions;
    private final KnowledgeDocumentService documentService;
    private final AssetService assets;
    private final AiGateway gateway;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> handle(Task task) {
        Long documentId = ((Number) task.getPayload().get("documentId")).longValue();
        KnowledgeDocument document = transactions.execute(tx -> documents.findById(documentId).orElse(null));
        if (document == null || !KnowledgeDocument.STATUS_PROCESSING.equals(document.getStatus())) {
            return Map.of("documentId", documentId, "skipped", true);
        }
        boolean brand = document.getBrandId() != null;

        String text;
        try {
            text = DocumentText.extract(document.getName(), assets.documentReference(document.getAssetId()).mimeType(),
                    assets.readDocument(document.getAssetId()));
        } catch (DocumentText.UnreadableException e) {
            return fail(documentId, e.getMessage());
        } catch (BizException e) {
            // Storage being down is worth another attempt; the last one tells the merchant instead of hanging.
            if (e.getErrorCode() == ErrorCode.STORAGE_UNAVAILABLE && task.getAttempts() < task.getMaxAttempts()) throw e;
            return fail(documentId, "文件读取失败，请重新上传");
        }
        int length = text.length();
        if (length > DocumentText.MAX_CHARS) text = text.substring(0, DocumentText.MAX_CHARS);

        if (!gateway.configured(ModelAlias.TEXT_WRITER)) return fail(documentId, "文本模型还没有配置，暂时无法整理");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("system", DocumentDigest.system(brand));
        options.put("temperature", 0.2);
        options.put("maxTokens", 4000);
        ProviderResult result;
        try {
            result = gateway.invoke(new ProviderRequest(ModelAlias.TEXT_WRITER, task.getTenantId(),
                    DocumentDigest.prompt(document.getName(), text), options, "knowledge-digest:" + task.getId()));
        } catch (RuntimeException e) {
            log.warn("文档整理调用文本模型失败: documentId={} reason={}", documentId, e.getMessage());
            return fail(documentId, "整理失败，请稍后点「重新整理」");
        }
        String reply = result.succeeded() && result.output() != null && result.output().get("text") instanceof String value
                ? value : null;
        DocumentDigest.Result digest = DocumentDigest.parse(json, reply, brand);
        if (digest == null) return fail(documentId, "整理结果无法识别，请点「重新整理」再试一次");

        Map<String, String> current;
        try {
            current = transactions.execute(tx -> documentService.currentValues(document, document.getCreatedBy()));
        } catch (BizException e) {
            return fail(documentId, brand ? "品牌不存在或已删除" : "门店不存在或已停用");
        }
        int saved = Objects.requireNonNullElse(transactions.execute(tx -> {
            KnowledgeDocument locked = documents.findForUpdate(documentId).orElse(null);
            if (locked == null || !KnowledgeDocument.STATUS_PROCESSING.equals(locked.getStatus())) return 0;
            suggestions.deletePending(documentId);
            // Reading again must not bring back what the merchant already accepted or dismissed.
            Set<String> handled = new HashSet<>();
            for (KnowledgeSuggestion earlier : suggestions.findByDocumentIdOrderByIdAsc(documentId)) {
                handled.add(earlier.getField() != null ? "field:" + earlier.getField() : "qa:" + normalize(earlier.getQuestion()));
            }
            int count = 0;
            for (var field : digest.fields().entrySet()) {
                if (handled.contains("field:" + field.getKey())
                        || field.getValue().equals(Objects.requireNonNullElse(current.get(field.getKey()), "").strip())) continue;
                KnowledgeSuggestion suggestion = suggestion(locked, KnowledgeSuggestion.KIND_FIELD);
                suggestion.setField(field.getKey());
                suggestion.setValue(field.getValue());
                suggestions.save(suggestion);
                count++;
            }
            for (var pair : digest.questions()) {
                if (handled.contains("qa:" + normalize(pair.getKey()))) continue;
                KnowledgeSuggestion suggestion = suggestion(locked, KnowledgeSuggestion.KIND_QA);
                suggestion.setQuestion(pair.getKey());
                suggestion.setAnswer(pair.getValue());
                suggestions.save(suggestion);
                count++;
            }
            locked.setStatus(KnowledgeDocument.STATUS_READY);
            locked.setCharCount(length);
            locked.setUpdatedAt(Instant.now());
            return count;
        }), 0);
        return Map.of("documentId", documentId, "suggestions", saved);
    }

    private static String normalize(String question) {
        return question == null ? "" : question.replaceAll("[\\p{P}\\s]", "");
    }

    private static KnowledgeSuggestion suggestion(KnowledgeDocument document, String kind) {
        KnowledgeSuggestion suggestion = new KnowledgeSuggestion();
        suggestion.setTenantId(document.getTenantId());
        suggestion.setDocumentId(document.getId());
        suggestion.setKind(kind);
        return suggestion;
    }

    private Map<String, Object> fail(Long documentId, String reason) {
        transactions.executeWithoutResult(tx -> documents.findForUpdate(documentId)
                .filter(document -> KnowledgeDocument.STATUS_PROCESSING.equals(document.getStatus()))
                .ifPresent(document -> {
                    document.setStatus(KnowledgeDocument.STATUS_FAILED);
                    document.setErrorMessage(reason);
                    document.setUpdatedAt(Instant.now());
                }));
        return Map.of("documentId", documentId, "failed", reason);
    }
}
