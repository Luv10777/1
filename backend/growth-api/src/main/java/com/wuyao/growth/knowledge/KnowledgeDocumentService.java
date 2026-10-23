package com.wuyao.growth.knowledge;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.brand.BrandDtos;
import com.wuyao.growth.brand.BrandService;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.store.StoreDtos;
import com.wuyao.growth.store.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Documents uploaded to a store or brand, and the suggestions the text model made from them. Nothing a
 * document says reaches the brand, the store or a FAQ set until the merchant accepts that suggestion;
 * accepting writes through the owning module, under its own rules (only the owner edits a brand).
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeSuggestionRepository suggestions;
    private final KnowledgeSetRepository sets;
    private final KnowledgeService knowledge;
    private final KnowledgeAccess access;
    private final AssetService assets;
    private final BrandService brands;
    private final StoreService stores;
    private final TaskService tasks;

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentDtos.DocumentView> list(Long storeId, Long brandId, Long userId) {
        access.requireRead(storeId, brandId, userId);
        List<KnowledgeDocument> found = storeId != null
                ? documents.findTop50ByStoreIdOrderByCreatedAtDesc(storeId)
                : documents.findTop50ByBrandIdOrderByCreatedAtDesc(brandId);
        Map<Long, Long> pending = new HashMap<>();
        if (!found.isEmpty()) {
            for (Object[] row : suggestions.countPending(found.stream().map(KnowledgeDocument::getId).toList())) {
                pending.put((Long) row[0], (Long) row[1]);
            }
        }
        return found.stream().map(document ->
                KnowledgeDocumentDtos.DocumentView.of(document, pending.getOrDefault(document.getId(), 0L))).toList();
    }

    @Transactional
    public KnowledgeDocumentDtos.DocumentView create(Long storeId, Long brandId, Long assetId, Long userId) {
        access.requireWrite(storeId, brandId, userId);
        AssetService.DocumentReference file = assets.documentReference(assetId);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTenantId(TenantContext.require());
        document.setStoreId(storeId);
        document.setBrandId(brandId);
        document.setAssetId(file.id());
        document.setName(file.name());
        document.setCreatedBy(userId);
        document = documents.save(document);
        submit(document, userId);
        return KnowledgeDocumentDtos.DocumentView.of(document, 0);
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentDtos.DocumentDetail get(Long id, Long userId) {
        KnowledgeDocument document = requireDocument(id);
        access.requireRead(document.getStoreId(), document.getBrandId(), userId);
        List<KnowledgeSuggestion> found = suggestions.findByDocumentIdOrderByIdAsc(id);
        Map<String, String> current = found.stream().anyMatch(s -> KnowledgeSuggestion.KIND_FIELD.equals(s.getKind()))
                ? currentValues(document, userId) : Map.of();
        boolean brand = document.getBrandId() != null;
        long pending = found.stream().filter(s -> KnowledgeSuggestion.STATUS_PENDING.equals(s.getStatus())).count();
        return new KnowledgeDocumentDtos.DocumentDetail(KnowledgeDocumentDtos.DocumentView.of(document, pending),
                found.stream().map(s -> {
                    DocumentDigest.Field field = s.getField() == null ? null : DocumentDigest.field(brand, s.getField());
                    return new KnowledgeDocumentDtos.SuggestionView(s.getId(), s.getKind(), s.getField(),
                            field == null ? null : field.label(), s.getField() == null ? null : current.get(s.getField()),
                            s.getValue(), s.getQuestion(), s.getAnswer(), s.getStatus());
                }).toList());
    }

    /** Reads the document again; suggestions not yet handled are replaced, handled ones are kept. */
    @Transactional
    public KnowledgeDocumentDtos.DocumentView retry(Long id, Long userId) {
        KnowledgeDocument document = documents.findForUpdate(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "文档不存在"));
        access.requireWrite(document.getStoreId(), document.getBrandId(), userId);
        if (KnowledgeDocument.STATUS_PROCESSING.equals(document.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT, "文档正在整理，请稍候");
        }
        document.setStatus(KnowledgeDocument.STATUS_PROCESSING);
        document.setErrorMessage(null);
        document.setUpdatedAt(Instant.now());
        submit(document, userId);
        return KnowledgeDocumentDtos.DocumentView.of(document, 0);
    }

    /** The uploaded file itself stays in the asset library. */
    @Transactional
    public void delete(Long id, Long userId) {
        KnowledgeDocument document = requireDocument(id);
        access.requireWrite(document.getStoreId(), document.getBrandId(), userId);
        documents.delete(document);
    }

    @Transactional
    public KnowledgeDocumentDtos.SuggestionView accept(Long suggestionId, KnowledgeDocumentDtos.AcceptRequest request,
                                                       Long userId) {
        KnowledgeSuggestion suggestion = requirePending(suggestionId);
        KnowledgeDocument document = requireDocument(suggestion.getDocumentId());
        access.requireWrite(document.getStoreId(), document.getBrandId(), userId);
        boolean brand = document.getBrandId() != null;
        if (KnowledgeSuggestion.KIND_FIELD.equals(suggestion.getKind())) {
            DocumentDigest.Field field = DocumentDigest.field(brand, suggestion.getField());
            String value = request == null || request.value() == null ? suggestion.getValue() : request.value().strip();
            if (field == null) throw BizException.of(ErrorCode.BAD_REQUEST, "这条建议的字段已不再支持");
            if (value.isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST, field.label() + "不能为空");
            if (value.length() > field.maxLength()) {
                throw BizException.of(ErrorCode.BAD_REQUEST, field.label() + "不能超过 " + field.maxLength() + " 字");
            }
            if (brand) updateBrand(document.getBrandId(), field.key(), value, userId);
            else updateStore(document.getStoreId(), field.key(), value, userId);
            suggestion.setValue(value);
        } else {
            String question = request == null || request.question() == null ? suggestion.getQuestion() : request.question().strip();
            String answer = request == null || request.answer() == null ? suggestion.getAnswer() : request.answer().strip();
            if (question.isEmpty() || answer.isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST, "问题和回答都要填写");
            Long setId = request == null ? null : request.knowledgeSetId();
            if (setId == null) throw BizException.of(ErrorCode.BAD_REQUEST, "请选择放进哪个问答集");
            KnowledgeSet set = sets.findById(setId)
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "问答集不存在"));
            if (!Objects.equals(set.getStoreId(), document.getStoreId()) || !Objects.equals(set.getBrandId(), document.getBrandId())) {
                throw BizException.of(ErrorCode.BAD_REQUEST, brand ? "请放进这个品牌的问答集" : "请放进这家门店的问答集");
            }
            knowledge.createEntry(setId, new KnowledgeDtos.CreateEntryRequest(question, answer,
                    KnowledgeEntry.SOURCE_DOCUMENT, KnowledgeEntry.STATUS_ACTIVE), userId);
            suggestion.setQuestion(question);
            suggestion.setAnswer(answer);
        }
        return settle(suggestion, KnowledgeSuggestion.STATUS_ACCEPTED, document);
    }

    @Transactional
    public KnowledgeDocumentDtos.SuggestionView dismiss(Long suggestionId, Long userId) {
        KnowledgeSuggestion suggestion = requirePending(suggestionId);
        KnowledgeDocument document = requireDocument(suggestion.getDocumentId());
        access.requireWrite(document.getStoreId(), document.getBrandId(), userId);
        return settle(suggestion, KnowledgeSuggestion.STATUS_DISMISSED, document);
    }

    /** What the owning brand or store says now for every field a document may suggest. */
    Map<String, String> currentValues(KnowledgeDocument document, Long userId) {
        Map<String, String> values = new HashMap<>();
        if (document.getBrandId() != null) {
            BrandDtos.View b = brands.get(document.getBrandId());
            values.put("slogan", b.slogan());
            values.put("intro", b.intro());
            values.put("positioning", b.positioning());
            values.put("targetAudience", b.targetAudience());
            values.put("languageStyle", b.languageStyle());
            values.put("history", b.history());
            values.put("brandStory", b.brandStory());
            values.put("coreTeam", b.coreTeam());
            values.put("culture", b.culture());
        } else {
            StoreDtos.StoreView s = stores.get(document.getStoreId(), userId);
            values.put("address", s.address());
            values.put("phone", s.phone());
            values.put("businessHours", s.businessHours());
            values.put("transportGuide", s.transportGuide());
        }
        return values;
    }

    private void updateBrand(Long brandId, String key, String value, Long userId) {
        BrandDtos.View b = brands.get(brandId);
        Map<String, String> v = new LinkedHashMap<>();
        v.put("slogan", b.slogan());
        v.put("intro", b.intro());
        v.put("positioning", b.positioning());
        v.put("targetAudience", b.targetAudience());
        v.put("languageStyle", b.languageStyle());
        v.put("history", b.history());
        v.put("brandStory", b.brandStory());
        v.put("coreTeam", b.coreTeam());
        v.put("culture", b.culture());
        v.put(key, value);
        brands.update(brandId, new BrandDtos.UpdateRequest(b.name(), b.industry(), v.get("slogan"), v.get("intro"),
                b.website(), v.get("positioning"), v.get("targetAudience"), v.get("languageStyle"), b.primaryColor(),
                b.viGuidelines(), b.logoAssetId(), b.miniProgramQrAssetId(), b.wechatQrAssetId(), v.get("history"),
                v.get("brandStory"), v.get("coreTeam"), v.get("culture"), b.version()), userId);
    }

    /** Name, address, phone and hours are replaced together, so the others are sent back as they are. */
    private void updateStore(Long storeId, String key, String value, Long userId) {
        StoreDtos.StoreView s = stores.get(storeId, userId);
        stores.update(storeId, new StoreDtos.UpdateRequest(s.name(),
                "address".equals(key) ? value : s.address(),
                "phone".equals(key) ? value : s.phone(),
                "businessHours".equals(key) ? value : s.businessHours(),
                s.version(), null, "transportGuide".equals(key) ? value : null, null, null), userId);
    }

    private KnowledgeDocumentDtos.SuggestionView settle(KnowledgeSuggestion suggestion, String status,
                                                        KnowledgeDocument document) {
        suggestion.setStatus(status);
        suggestion.setUpdatedAt(Instant.now());
        DocumentDigest.Field field = suggestion.getField() == null ? null
                : DocumentDigest.field(document.getBrandId() != null, suggestion.getField());
        return new KnowledgeDocumentDtos.SuggestionView(suggestion.getId(), suggestion.getKind(), suggestion.getField(),
                field == null ? null : field.label(), null, suggestion.getValue(), suggestion.getQuestion(),
                suggestion.getAnswer(), status);
    }

    private void submit(KnowledgeDocument document, Long userId) {
        tasks.submit(KnowledgeDocumentDigestHandler.TYPE, "DEFAULT", Map.of("documentId", document.getId()),
                "knowledge-digest:" + document.getId() + ":" + document.getUpdatedAt().toEpochMilli(), userId);
    }

    private KnowledgeDocument requireDocument(Long id) {
        return documents.findById(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "文档不存在"));
    }

    private KnowledgeSuggestion requirePending(Long id) {
        KnowledgeSuggestion suggestion = suggestions.findForUpdate(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "建议不存在"));
        if (!KnowledgeSuggestion.STATUS_PENDING.equals(suggestion.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT, "这条建议已经处理过了");
        }
        return suggestion;
    }
}
