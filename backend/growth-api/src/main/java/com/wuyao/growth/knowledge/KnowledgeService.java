package com.wuyao.growth.knowledge;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A set belongs to a store, or to a brand and is shared by all of the brand's stores. It is active or
 * archived; an entry is switched on or off. A live session started from now on knows every switched-on
 * entry of the store's active FAQ sets and its brand's. An archived set is read-only until restored.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeSetRepository setRepository;
    private final KnowledgeEntryRepository entryRepository;
    private final KnowledgeAccess access;

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDtos.SetView> listSets(Long storeId, String kind, int page, int size, Long userId) {
        validatePage(page, size);
        access.requireRead(storeId, null, userId);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        var result = kind == null || kind.isBlank()
                ? setRepository.findByStoreIdOrderByUpdatedAtDesc(storeId, pageable)
                : setRepository.findByStoreIdAndKindOrderByUpdatedAtDesc(storeId, kind, pageable);
        return PageResult.of(result, result.getContent().stream().map(KnowledgeDtos.SetView::of).toList());
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDtos.SetView> listBrandSets(Long brandId, String kind, int page, int size, Long userId) {
        validatePage(page, size);
        access.requireRead(null, brandId, userId);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        var result = kind == null || kind.isBlank()
                ? setRepository.findByBrandIdOrderByUpdatedAtDesc(brandId, pageable)
                : setRepository.findByBrandIdAndKindOrderByUpdatedAtDesc(brandId, kind, pageable);
        return PageResult.of(result, result.getContent().stream().map(KnowledgeDtos.SetView::of).toList());
    }

    @Transactional
    public KnowledgeDtos.SetView createSet(Long storeId, KnowledgeDtos.CreateSetRequest request, Long userId) {
        return createSet(storeId, null, request, userId);
    }

    @Transactional
    public KnowledgeDtos.SetView createBrandSet(Long brandId, KnowledgeDtos.CreateSetRequest request, Long userId) {
        return createSet(null, brandId, request, userId);
    }

    private KnowledgeDtos.SetView createSet(Long storeId, Long brandId, KnowledgeDtos.CreateSetRequest request, Long userId) {
        access.requireWrite(storeId, brandId, userId);
        String kind = defaultValue(request.kind(), KnowledgeSet.KIND_FAQ);
        validateKind(kind);
        KnowledgeSet set = new KnowledgeSet();
        set.setTenantId(TenantContext.require());
        set.setStoreId(storeId);
        set.setBrandId(brandId);
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
        access.requireRead(set.getStoreId(), set.getBrandId(), userId);
        return KnowledgeDtos.SetView.of(set);
    }

    @Transactional
    public KnowledgeDtos.SetView updateSet(Long id, KnowledgeDtos.UpdateSetRequest request, Long userId) {
        KnowledgeSet set = requireActiveSet(id);
        access.requireWrite(set.getStoreId(), set.getBrandId(), userId);
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
        access.requireRead(set.getStoreId(), set.getBrandId(), userId);
        var result = entryRepository.findByKnowledgeSetIdOrderByUpdatedAtDesc(setId, PageRequest.of(page, size));
        return PageResult.of(result, result.getContent().stream().map(KnowledgeDtos.EntryView::of).toList());
    }

    @Transactional
    public KnowledgeDtos.EntryView createEntry(Long setId, KnowledgeDtos.CreateEntryRequest request, Long userId) {
        KnowledgeSet set = requireActiveFaqSet(setId);
        access.requireWrite(set.getStoreId(), set.getBrandId(), userId);
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setTenantId(TenantContext.require());
        entry.setStoreId(set.getStoreId());
        entry.setBrandId(set.getBrandId());
        entry.setKnowledgeSetId(setId);
        entry.setSource(defaultValue(request.source(), KnowledgeEntry.SOURCE_MANUAL));
        entry.setStatus(defaultValue(request.status(), KnowledgeEntry.STATUS_ACTIVE));
        entry.setQuestion(request.question().trim());
        entry.setAnswer(request.answer().trim());
        entry.setCreatedBy(userId);
        touch(entry);
        return KnowledgeDtos.EntryView.of(entryRepository.save(entry));
    }

    @Transactional
    public KnowledgeDtos.EntryView updateEntry(Long entryId, KnowledgeDtos.UpdateEntryRequest request, Long userId) {
        KnowledgeEntry entry = requireWritableEntry(entryId, userId);
        if (request.question() != null) {
            if (request.question().isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST, "问题不能为空");
            entry.setQuestion(request.question().trim());
        }
        if (request.answer() != null) {
            if (request.answer().isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST, "回答不能为空");
            entry.setAnswer(request.answer().trim());
        }
        if (request.status() != null) entry.setStatus(request.status());
        touch(entry);
        return KnowledgeDtos.EntryView.of(entry);
    }

    /** Removes the entry for good; a session already started keeps it in its own snapshot. */
    @Transactional
    public void deleteEntry(Long entryId, Long userId) {
        entryRepository.delete(requireWritableEntry(entryId, userId));
    }

    /** Entries keep their own switch, so restoring brings the set back exactly as it was. */
    @Transactional
    public KnowledgeDtos.SetView archive(Long setId, Long userId) {
        return setStatus(setId, KnowledgeSet.STATUS_ARCHIVED, userId);
    }

    @Transactional
    public KnowledgeDtos.SetView restore(Long setId, Long userId) {
        return setStatus(setId, KnowledgeSet.STATUS_ACTIVE, userId);
    }

    @Transactional(readOnly = true)
    public KnowledgeDtos.KnowledgeContext context(Long storeId, Long userId) {
        Long brandId = access.brandOf(storeId, userId).orElse(null);
        List<KnowledgeEntry> entries = new ArrayList<>(activeEntries(
                setRepository.findByStoreIdAndKindAndStatusOrderByIdAsc(storeId, KnowledgeSet.KIND_FAQ, KnowledgeSet.STATUS_ACTIVE)));
        if (brandId != null) {
            entries.addAll(activeEntries(setRepository.findByBrandIdAndKindAndStatusOrderByIdAsc(
                    brandId, KnowledgeSet.KIND_FAQ, KnowledgeSet.STATUS_ACTIVE)));
        }
        return new KnowledgeDtos.KnowledgeContext(storeId, brandId,
                entries.stream().map(KnowledgeDtos.EntryView::of).toList(), Instant.now());
    }

    private List<KnowledgeEntry> activeEntries(List<KnowledgeSet> sets) {
        List<Long> ids = sets.stream().map(KnowledgeSet::getId).toList();
        return ids.isEmpty() ? List.of() : entryRepository.findByKnowledgeSetIdInAndStatusOrderByIdAsc(ids, KnowledgeEntry.STATUS_ACTIVE);
    }

    private KnowledgeDtos.SetView setStatus(Long setId, String status, Long userId) {
        KnowledgeSet set = requireSet(setId);
        access.requireWrite(set.getStoreId(), set.getBrandId(), userId);
        set.setStatus(status);
        touch(set);
        return KnowledgeDtos.SetView.of(set);
    }

    private KnowledgeSet requireSet(Long id) {
        return setRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "知识集不存在"));
    }

    private KnowledgeSet requireActiveSet(Long id) {
        KnowledgeSet set = requireSet(id);
        if (KnowledgeSet.STATUS_ARCHIVED.equals(set.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT, "知识集已归档，恢复后才能修改");
        }
        return set;
    }

    private KnowledgeSet requireActiveFaqSet(Long id) {
        KnowledgeSet set = requireActiveSet(id);
        if (!KnowledgeSet.KIND_FAQ.equals(set.getKind())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "文档知识集不支持问答操作");
        }
        return set;
    }

    private KnowledgeEntry requireWritableEntry(Long entryId, Long userId) {
        KnowledgeEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "知识条目不存在"));
        access.requireWrite(entry.getStoreId(), entry.getBrandId(), userId);
        requireActiveSet(entry.getKnowledgeSetId());
        return entry;
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
