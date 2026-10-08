package com.wuyao.growth.knowledge;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.product.Product;
import com.wuyao.growth.product.ProductRepository;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeSetRepository setRepository;
    private final KnowledgeEntryRepository entryRepository;
    private final StoreAccessService storeAccessService;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDtos.SetView> listSets(Long storeId, String kind, int page, int size, Long userId) {
        validatePage(page, size);
        storeAccessService.requireAccess(storeId, userId);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        var result = kind == null || kind.isBlank()
                ? setRepository.findByStoreIdOrderByUpdatedAtDesc(storeId, pageable)
                : setRepository.findByStoreIdAndKindOrderByUpdatedAtDesc(storeId, kind, pageable);
        return PageResult.of(result, result.getContent().stream().map(KnowledgeDtos.SetView::of).toList());
    }

    @Transactional
    public KnowledgeDtos.SetView createSet(Long storeId, KnowledgeDtos.CreateSetRequest request, Long userId) {
        storeAccessService.requireAccess(storeId, userId);
        String kind = defaultValue(request.kind(), KnowledgeSet.KIND_FAQ);
        validateKind(kind);
        KnowledgeSet set = new KnowledgeSet();
        set.setTenantId(TenantContext.require());
        set.setStoreId(storeId);
        set.setName(request.name().trim());
        set.setKind(kind);
        set.setDescription(trimToNull(request.description()));
        set.setCreatedBy(userId);
        touch(set);
        return KnowledgeDtos.SetView.of(setRepository.save(set));
    }

    @Transactional(readOnly = true)
    public KnowledgeDtos.SetView getSet(Long id, Long userId) {
        KnowledgeSet set = requireSet(id);
        storeAccessService.requireAccess(set.getStoreId(), userId);
        return KnowledgeDtos.SetView.of(set);
    }

    @Transactional
    public KnowledgeDtos.SetView updateSet(Long id, KnowledgeDtos.UpdateSetRequest request, Long userId) {
        KnowledgeSet set = requireSet(id);
        storeAccessService.requireAccess(set.getStoreId(), userId);
        if (request.name() != null) {
            if (request.name().isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST, "知识集名称不能为空");
            set.setName(request.name().trim());
        }
        if (request.kind() != null) validateKind(request.kind());
        if (request.kind() != null && !request.kind().equals(set.getKind()) && hasEntries(set.getId())) {
            throw BizException.of(ErrorCode.CONFLICT, "已有问答的知识集不能切换类型");
        }
        if (request.kind() != null) set.setKind(request.kind());
        if (request.description() != null) set.setDescription(trimToNull(request.description()));
        touch(set);
        return KnowledgeDtos.SetView.of(set);
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDtos.EntryView> listEntries(Long setId, int page, int size, Long userId) {
        validatePage(page, size);
        KnowledgeSet set = requireSet(setId);
        storeAccessService.requireAccess(set.getStoreId(), userId);
        var result = entryRepository.findByKnowledgeSetIdOrderByUpdatedAtDesc(setId, PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(KnowledgeDtos.EntryView::of).toList());
    }

    @Transactional
    public KnowledgeDtos.EntryView createEntry(Long setId, KnowledgeDtos.CreateEntryRequest request, Long userId) {
        KnowledgeSet set = requireFaqSet(setId);
        storeAccessService.requireAccess(set.getStoreId(), userId);
        String scope = defaultValue(request.scope(), KnowledgeEntry.SCOPE_STORE);
        String source = defaultValue(request.source(), KnowledgeEntry.SOURCE_MANUAL);
        String status = defaultValue(request.status(), KnowledgeEntry.STATUS_DRAFT);
        Long productId = validateScopeAndProduct(scope, request.productId(), set.getStoreId());
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setTenantId(TenantContext.require());
        entry.setStoreId(set.getStoreId());
        entry.setKnowledgeSetId(setId);
        entry.setProductId(productId);
        entry.setScope(scope);
        entry.setSource(source);
        entry.setStatus(status);
        entry.setQuestion(request.question().trim());
        entry.setAnswer(request.answer().trim());
        entry.setCreatedBy(userId);
        touch(entry);
        return KnowledgeDtos.EntryView.of(entryRepository.save(entry));
    }

    @Transactional
    public KnowledgeDtos.EntryView updateEntry(Long entryId, KnowledgeDtos.UpdateEntryRequest request, Long userId) {
        KnowledgeEntry entry = requireEntry(entryId);
        storeAccessService.requireAccess(entry.getStoreId(), userId);
        String scope = request.scope() == null ? entry.getScope() : request.scope();
        Long requestedProductId = request.productId() == null ? entry.getProductId() : request.productId();
        Long productId = validateScopeAndProduct(scope, requestedProductId, entry.getStoreId());
        if (request.question() != null) {
            if (request.question().isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST, "问题不能为空");
            entry.setQuestion(request.question().trim());
        }
        if (request.answer() != null) {
            if (request.answer().isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST, "回答不能为空");
            entry.setAnswer(request.answer().trim());
        }
        entry.setScope(scope);
        entry.setProductId(productId);
        if (request.source() != null) entry.setSource(request.source());
        if (request.status() != null) entry.setStatus(request.status());
        entry.setManualCorrections(entry.getManualCorrections() + 1);
        entry.setVersion(entry.getVersion() + 1);
        touch(entry);
        return KnowledgeDtos.EntryView.of(entry);
    }

    @Transactional
    public void deleteEntry(Long entryId, Long userId) {
        KnowledgeEntry entry = requireEntry(entryId);
        storeAccessService.requireAccess(entry.getStoreId(), userId);
        entry.setStatus(KnowledgeEntry.STATUS_DISABLED);
        entry.setVersion(entry.getVersion() + 1);
        touch(entry);
    }

    @Transactional
    public KnowledgeDtos.SetView publish(Long setId, Long userId) {
        KnowledgeSet set = requireFaqSet(setId);
        storeAccessService.requireAccess(set.getStoreId(), userId);
        entryRepository.findByKnowledgeSetIdAndStatusOrderByIdAsc(setId, KnowledgeEntry.STATUS_DRAFT)
                .forEach(entry -> {
                    entry.setStatus(KnowledgeEntry.STATUS_ACTIVE);
                    entry.setVersion(entry.getVersion() + 1);
                    touch(entry);
                });
        set.setStatus(KnowledgeSet.STATUS_PUBLISHED);
        set.setCurrentVersion(set.getCurrentVersion() + 1);
        set.setPublishedAt(Instant.now());
        touch(set);
        return KnowledgeDtos.SetView.of(set);
    }

    @Transactional
    public KnowledgeDtos.SetView disable(Long setId, Long userId) {
        KnowledgeSet set = requireSet(setId);
        storeAccessService.requireAccess(set.getStoreId(), userId);
        set.setStatus(KnowledgeSet.STATUS_DISABLED);
        touch(set);
        return KnowledgeDtos.SetView.of(set);
    }

    @Transactional
    public KnowledgeDtos.SetView archive(Long setId, Long userId) {
        KnowledgeSet set = requireSet(setId);
        storeAccessService.requireAccess(set.getStoreId(), userId);
        set.setStatus(KnowledgeSet.STATUS_ARCHIVED);
        entryRepository.findByKnowledgeSetIdAndStatusOrderByIdAsc(setId, KnowledgeEntry.STATUS_ACTIVE)
                .forEach(entry -> {
                    entry.setStatus(KnowledgeEntry.STATUS_DISABLED);
                    entry.setVersion(entry.getVersion() + 1);
                    touch(entry);
                });
        touch(set);
        return KnowledgeDtos.SetView.of(set);
    }

    @Transactional(readOnly = true)
    public KnowledgeDtos.KnowledgeContext context(Long storeId, List<Long> productIds, Long userId) {
        storeAccessService.requireAccess(storeId, userId);
        List<KnowledgeSet> sets = setRepository.findByStoreIdAndKindAndStatusOrderByIdAsc(
                storeId, KnowledgeSet.KIND_FAQ, KnowledgeSet.STATUS_PUBLISHED);
        List<Long> setIds = sets.stream().map(KnowledgeSet::getId).toList();
        List<KnowledgeEntry> entries = new ArrayList<>();
        if (!setIds.isEmpty()) {
            entries.addAll(entryRepository.findByStoreIdAndKnowledgeSetIdInAndScopeAndStatusOrderByIdAsc(
                    storeId, setIds, KnowledgeEntry.SCOPE_STORE, KnowledgeEntry.STATUS_ACTIVE));
            if (productIds != null && !productIds.isEmpty()) {
                for (Long productId : productIds) {
                    requireProductInStore(productId, storeId);
                }
                entries.addAll(entryRepository.findByStoreIdAndKnowledgeSetIdInAndScopeAndProductIdInAndStatusOrderByIdAsc(
                        storeId, setIds, KnowledgeEntry.SCOPE_PRODUCT, productIds, KnowledgeEntry.STATUS_ACTIVE));
            }
        }
        entries.sort(Comparator.comparing(KnowledgeEntry::getId));
        int version = sets.stream().map(KnowledgeSet::getCurrentVersion).max(Integer::compareTo).orElse(0);
        return new KnowledgeDtos.KnowledgeContext(storeId, version,
                entries.stream().map(KnowledgeDtos.EntryView::of).toList(), Instant.now());
    }

    private KnowledgeSet requireSet(Long id) {
        return setRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "知识集不存在"));
    }

    private KnowledgeSet requireFaqSet(Long id) {
        KnowledgeSet set = requireSet(id);
        if (!KnowledgeSet.KIND_FAQ.equals(set.getKind())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "文档知识集不支持问答操作");
        }
        return set;
    }

    private KnowledgeEntry requireEntry(Long id) {
        return entryRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "知识条目不存在"));
    }

    private Long validateScopeAndProduct(String scope, Long productId, Long storeId) {
        if (!KnowledgeEntry.SCOPE_STORE.equals(scope) && !KnowledgeEntry.SCOPE_PRODUCT.equals(scope)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "关联范围不合法");
        }
        if (KnowledgeEntry.SCOPE_STORE.equals(scope)) {
            if (productId != null) throw BizException.of(ErrorCode.BAD_REQUEST, "门店通用问答不能关联商品");
            return null;
        }
        if (productId == null) throw BizException.of(ErrorCode.BAD_REQUEST, "商品问答必须关联商品");
        requireProductInStore(productId, storeId);
        return productId;
    }

    private void requireProductInStore(Long productId, Long storeId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "商品不存在"));
        if (!storeId.equals(product.getStoreId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "商品不属于当前门店");
        }
    }

    private boolean hasEntries(Long setId) {
        return entryRepository.existsByKnowledgeSetId(setId);
    }

    private static void validateKind(String kind) {
        if (!KnowledgeSet.KIND_FAQ.equals(kind) && !KnowledgeSet.KIND_DOCUMENT.equals(kind)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "知识集类型不合法");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "page 必须非负，size 必须在 1 到 100 之间");
        }
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void touch(KnowledgeSet set) {
        set.setUpdatedAt(Instant.now());
    }

    private static void touch(KnowledgeEntry entry) {
        entry.setUpdatedAt(Instant.now());
    }
}
