package com.wuyao.growth.live;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.knowledge.KnowledgeDtos;
import com.wuyao.growth.knowledge.KnowledgeService;
import com.wuyao.growth.product.ProductDtos;
import com.wuyao.growth.product.ProductService;
import com.wuyao.growth.live.speech.LiveVoice;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.voice.VoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LiveSessionService {

    private static final int SESSION_PRIORITY = 1;
    private static final int PRODUCT_PRIORITY = 2;
    private static final int STORE_PRIORITY = 3;

    private final LiveSessionRepository sessions;
    private final LiveSessionProductRepository sessionProducts;
    private final LiveSessionQaRepository qas;
    private final LiveKnowledgeSnapshotRepository snapshots;
    private final ProductService products;
    private final KnowledgeService knowledge;
    private final StoreAccessService stores;
    private final VoiceService voices;

    /** A session that is on air or merely paused. A store has at most one. */
    public static final Set<String> ACTIVE = Set.of("LIVE", "PAUSED");

    @Transactional(readOnly = true)
    public List<LiveDtos.Summary> list(Long storeId, Long userId) {
        stores.requireAccess(storeId, userId);
        return sessions.findByStoreIdOrderByUpdatedAtDescIdDesc(storeId).stream()
                .map(session -> new LiveDtos.Summary(session.getId(), session.getStoreId(), session.getName(),
                        session.getStatus(), session.getStartedAt(), session.getEndedAt(), session.getUpdatedAt()))
                .toList();
    }

    @Transactional
    public LiveDtos.View create(Long storeId, LiveDtos.CreateRequest request, Long userId) {
        stores.requireAccess(storeId, userId);
        LiveSession session = new LiveSession();
        session.setTenantId(TenantContext.require());
        session.setStoreId(storeId);
        session.setName(requiredText(request.name(), "直播名称", 160));
        session.setRoomId(roomId(request.roomId()));
        if (request.config() != null) session.setConfig(request.config());
        if (request.audioRoute() != null) session.setAudioRoute(request.audioRoute());
        if (request.audioTestStatus() != null) session.setAudioTestStatus(request.audioTestStatus());
        session.setCreatedBy(userId);
        session = sessions.save(session);
        replaceProducts(session, request.productIds(), userId);
        return view(session);
    }

    @Transactional(readOnly = true)
    public LiveDtos.View get(Long id, Long userId) {
        LiveSession session = require(id, userId, false);
        return view(session);
    }

    @Transactional
    public LiveDtos.View update(Long id, LiveDtos.UpdateRequest request, Long userId) {
        LiveSession session = require(id, userId, true);
        requireDraft(session);
        if (request.version() != null && !request.version().equals(session.getVersion())) {
            throw BizException.of(ErrorCode.CONFLICT, "直播配置已被修改，请刷新后重试");
        }
        if (request.name() != null) session.setName(requiredText(request.name(), "直播名称", 160));
        if (request.roomId() != null) session.setRoomId(roomId(request.roomId()));
        if (request.productIds() != null) replaceProducts(session, request.productIds(), userId);
        if (request.config() != null) session.setConfig(request.config());
        if (request.audioRoute() != null) session.setAudioRoute(request.audioRoute());
        if (request.audioTestStatus() != null) session.setAudioTestStatus(request.audioTestStatus());
        return saveView(session);
    }

    @Transactional
    public LiveDtos.View updateAudioRoute(Long id, LiveDtos.AudioRouteRequest request, Long userId) {
        LiveSession session = require(id, userId, true);
        requireDraft(session);
        if (request.audioRoute() != null) session.setAudioRoute(request.audioRoute());
        if (request.audioTestStatus() != null) session.setAudioTestStatus(request.audioTestStatus());
        return saveView(session);
    }

    /** Only stores a room identifier. Resolving platform URLs requires a configured connector. */
    @Transactional
    public LiveDtos.View parseLink(Long id, String roomId, Long userId) {
        LiveSession session = require(id, userId, true);
        requireDraft(session);
        session.setRoomId(requiredText(roomId, "直播间标识", 200));
        return saveView(session);
    }

    /** Starts the configured session lifecycle; external streaming is not implemented here. */
    @Transactional
    public LiveDtos.View start(Long id, Long userId) {
        LiveSession session = require(id, userId, true);
        requireState(session, Set.of("DRAFT"));
        // The room link stays optional: nothing reads it until a comment source is connected,
        // and the audio is played by the console itself, which needs no room.
        List<LiveSessionProduct> selected = selectedProducts(id);
        if (selected.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "至少选择一个商品后才能开播");
        }
        // Revalidate here because products may have been archived since configuration was saved.
        selected.forEach(product -> requireProductInStore(session, product.getProductId(), userId));
        requireUsableVoices(session, userId);
        sessions.findByStoreIdAndStatusIn(session.getStoreId(), ACTIVE).stream()
                .filter(other -> !other.getId().equals(id)).findFirst().ifPresent(other -> {
                    throw BizException.of(ErrorCode.CONFLICT,
                            "门店已有一场正在进行的直播「" + other.getName() + "」，请先结束它再开始新的一场");
                });
        buildSnapshot(session, selected, userId);
        session.setStatus("LIVE");
        session.setStartedAt(Instant.now());
        try {
            return saveView(session);
        } catch (DataIntegrityViolationException raced) {
            // Two sessions of one store started at the same moment; the database let only one through.
            throw BizException.of(ErrorCode.CONFLICT, "门店已有一场正在进行的直播，请先结束它再开始新的一场");
        }
    }

    /**
     * Configuration is frozen once a session starts, so a session that starts without a voice it
     * can actually use could never speak. Refuse to start it instead.
     */
    private void requireUsableVoices(LiveSession session, Long userId) {
        List<String> lineup = LiveVoice.lineup(session.getConfig());
        if (lineup.isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST, "请先选择本场的主播音色");
        for (String id : lineup) {
            LiveVoice voice = LiveVoice.parse(id);
            voices.resolveVoice(session.getStoreId(), voice.sampleId(), voice.builtInVoice(), userId);
        }
    }

    /** Only a session that never started can be removed; anything that went on air stays as history. */
    @Transactional
    public void delete(Long id, Long userId) {
        LiveSession session = require(id, userId, true);
        if (!"DRAFT".equals(session.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT, "只有还没开始的场次可以删除");
        }
        sessions.delete(session);
    }

    /**
     * Starts the next session from an earlier one: same voices, style, products and session answers.
     * Products that have since left the library are dropped, along with the answers tied to them.
     */
    @Transactional
    public LiveDtos.View duplicate(Long id, LiveDtos.DuplicateRequest request, Long userId) {
        LiveSession source = require(id, userId, false);
        LiveSession copy = new LiveSession();
        copy.setTenantId(source.getTenantId());
        copy.setStoreId(source.getStoreId());
        String name = request == null || request.name() == null || request.name().isBlank()
                ? source.getName() : request.name();
        copy.setName(requiredText(name, "直播名称", 160));
        copy.setRoomId(source.getRoomId());
        copy.setConfig(source.getConfig());
        copy.setAudioRoute(source.getAudioRoute());
        copy.setCreatedBy(userId);
        LiveSession saved = sessions.save(copy);
        List<Long> kept = selectedProducts(id).stream().map(LiveSessionProduct::getProductId)
                .filter(productId -> stillInStore(source, productId, userId)).toList();
        replaceProducts(saved, kept, userId);
        for (LiveSessionQa qa : qas.findBySessionIdOrderByIdAsc(id)) {
            boolean productScoped = !"STORE_KNOWLEDGE".equals(qa.getPersistMode()) && qa.getTargetId() != null;
            if (productScoped && !kept.contains(qa.getTargetId())) continue;
            LiveSessionQa clone = new LiveSessionQa();
            clone.setTenantId(saved.getTenantId());
            clone.setSessionId(saved.getId());
            clone.setQuestion(qa.getQuestion());
            clone.setAnswer(qa.getAnswer());
            // Where the original was also filed is kept as a label; nothing is written to a library again.
            clone.setPersistMode(qa.getPersistMode());
            clone.setTargetId(qa.getTargetId());
            clone.setCreatedBy(userId);
            qas.save(clone);
        }
        return view(saved);
    }

    private boolean stillInStore(LiveSession session, Long productId, Long userId) {
        try {
            return session.getStoreId().equals(products.get(productId, userId).storeId());
        } catch (BizException gone) {
            return false;
        }
    }

    @Transactional
    public LiveDtos.View pause(Long id, Long userId) {
        return transition(id, "PAUSED", Set.of("LIVE"), userId);
    }

    @Transactional
    public LiveDtos.View resume(Long id, Long userId) {
        // Keep the knowledge captured at start, even if a library has changed during the pause.
        return transition(id, "LIVE", Set.of("PAUSED"), userId);
    }

    @Transactional
    public LiveDtos.View end(Long id, Long userId) {
        return transition(id, "ENDED", Set.of("LIVE", "PAUSED"), userId);
    }

    @Transactional
    public LiveDtos.QaView addQa(Long id, LiveDtos.QaRequest request, Long userId) {
        LiveSession session = require(id, userId, true);
        requireState(session, Set.of("DRAFT", "LIVE", "PAUSED"));
        String question = requiredText(request.question(), "问题", 500);
        String answer = requiredText(request.answer(), "回答", 4000);
        String mode = request.persistMode() == null ? "SESSION" : request.persistMode();
        Long targetId = request.targetId();

        switch (mode) {
            case "SESSION" -> {
                if (targetId != null) requireSelectedProduct(session, targetId, userId);
            }
            case "PRODUCT_FAQ" -> {
                requireTarget(targetId);
                requireSelectedProduct(session, targetId, userId);
                products.createFaq(targetId, new ProductDtos.FaqRequest(question, answer, null), userId);
            }
            case "STORE_KNOWLEDGE" -> {
                requireTarget(targetId);
                KnowledgeDtos.SetView set = knowledge.getSet(targetId, userId);
                if (!session.getStoreId().equals(set.storeId())) {
                    throw BizException.of(ErrorCode.FORBIDDEN, "知识集不属于当前门店");
                }
                if (!"FAQ".equals(set.kind()) || "ARCHIVED".equals(set.status())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "请选择未归档的问答知识集");
                }
                // Persist as a draft; publishing is an explicit knowledge-library operation.
                knowledge.createEntry(targetId, new KnowledgeDtos.CreateEntryRequest(
                        question, answer, "STORE", null, "LIVE_DRAFT", "DRAFT"), userId);
            }
            default -> throw BizException.of(ErrorCode.BAD_REQUEST, "问答保存方式不合法");
        }

        LiveSessionQa qa = new LiveSessionQa();
        qa.setTenantId(session.getTenantId());
        qa.setSessionId(id);
        qa.setQuestion(question);
        qa.setAnswer(answer);
        qa.setPersistMode(mode);
        qa.setTargetId(targetId);
        qa.setCreatedBy(userId);
        qa = qas.save(qa);
        if (!"DRAFT".equals(session.getStatus())) {
            // Append the explicit session override without refreshing inherited library content.
            saveSnapshot(session, "SESSION", qa.getId(), question, answer, SESSION_PRIORITY);
        }
        session.setUpdatedAt(Instant.now());
        return toQa(qa);
    }

    @Transactional(readOnly = true)
    public List<LiveDtos.QaView> listQa(Long id, Long userId) {
        require(id, userId, false);
        return qas.findBySessionIdOrderByIdAsc(id).stream().map(this::toQa).toList();
    }

    /** Editing a session override never silently rewrites its previously persisted library copy. */
    @Transactional
    public LiveDtos.QaView updateQa(Long id, Long qaId, LiveDtos.QaUpdateRequest request, Long userId) {
        LiveSession session = require(id, userId, true);
        requireDraft(session);
        LiveSessionQa qa = requireQa(id, qaId, request.version());
        qa.setQuestion(requiredText(request.question(), "问题", 500));
        qa.setAnswer(requiredText(request.answer(), "回答", 4000));
        session.setUpdatedAt(Instant.now());
        return toQa(qas.saveAndFlush(qa));
    }

    @Transactional
    public void deleteQa(Long id, Long qaId, Long version, Long userId) {
        LiveSession session = require(id, userId, true);
        requireDraft(session);
        qas.delete(requireQa(id, qaId, version));
        session.setUpdatedAt(Instant.now());
    }

    private LiveSessionQa requireQa(Long sessionId, Long qaId, Long version) {
        LiveSessionQa qa = qas.findById(qaId)
                .filter(value -> sessionId.equals(value.getSessionId()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "本场问答不存在"));
        if (version == null || !version.equals(qa.getVersion())) {
            throw BizException.of(ErrorCode.CONFLICT, "问答已被修改，请刷新后重试");
        }
        return qa;
    }

    private LiveSession require(Long id, Long userId, boolean lock) {
        LiveSession session = (lock ? sessions.findForUpdate(id) : sessions.findById(id))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "直播场次不存在"));
        stores.requireAccess(session.getStoreId(), userId);
        return session;
    }

    private LiveDtos.View transition(Long id, String nextState, Set<String> allowed, Long userId) {
        LiveSession session = require(id, userId, true);
        requireState(session, allowed);
        session.setStatus(nextState);
        if ("ENDED".equals(nextState)) session.setEndedAt(Instant.now());
        return saveView(session);
    }

    private void replaceProducts(LiveSession session, List<Long> ids, Long userId) {
        Set<Long> uniqueIds = ids == null ? Set.of() : new LinkedHashSet<>(ids);
        if (uniqueIds.size() > 100) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "单场最多选择 100 个商品");
        }
        uniqueIds.forEach(productId -> requireProductInStore(session, productId, userId));
        sessionProducts.deleteBySessionId(session.getId());
        // Flush deletes before inserts so retaining a product does not violate the unique key.
        sessionProducts.flush();
        int sortOrder = 0;
        for (Long productId : uniqueIds) {
            LiveSessionProduct selected = new LiveSessionProduct();
            selected.setTenantId(session.getTenantId());
            selected.setSessionId(session.getId());
            selected.setProductId(productId);
            selected.setSortOrder(sortOrder++);
            sessionProducts.save(selected);
        }
    }

    private void requireProductInStore(LiveSession session, Long productId, Long userId) {
        if (productId == null || productId <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "商品 ID 不合法");
        }
        ProductDtos.View product = products.get(productId, userId);
        if (!session.getStoreId().equals(product.storeId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "商品不属于当前门店");
        }
    }

    private void requireSelectedProduct(LiveSession session, Long productId, Long userId) {
        if (selectedProducts(session.getId()).stream().noneMatch(p -> productId.equals(p.getProductId()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择本场已关联的商品");
        }
        requireProductInStore(session, productId, userId);
    }

    private void buildSnapshot(LiveSession session, List<LiveSessionProduct> selected, Long userId) {
        List<Long> productIds = selected.stream().map(LiveSessionProduct::getProductId).toList();
        KnowledgeDtos.KnowledgeContext context = knowledge.context(session.getStoreId(), productIds, userId);
        snapshots.deleteBySessionId(session.getId());
        for (LiveSessionQa qa : qas.findBySessionIdOrderByIdAsc(session.getId())) {
            // A product-scoped override must stop participating when its product is removed.
            if (!"STORE_KNOWLEDGE".equals(qa.getPersistMode()) && qa.getTargetId() != null
                    && !productIds.contains(qa.getTargetId())) continue;
            saveSnapshot(session, "SESSION", qa.getId(), qa.getQuestion(), qa.getAnswer(), SESSION_PRIORITY);
        }
        for (Long productId : productIds) {
            for (ProductDtos.FaqListView faq : products.listFaqs(productId, userId)) {
                if ("ACTIVE".equals(faq.status())) {
                    saveSnapshot(session, "PRODUCT_FAQ", faq.id(), faq.question(), faq.answer(), PRODUCT_PRIORITY);
                }
            }
        }
        for (KnowledgeDtos.EntryView entry : context.entries()) {
            int priority = "PRODUCT".equals(entry.scope()) ? PRODUCT_PRIORITY : STORE_PRIORITY;
            saveSnapshot(session, entry.scope(), entry.id(), entry.question(), entry.answer(), priority);
        }
        session.setKnowledgeVersion(context.version());
    }

    private void saveSnapshot(LiveSession session, String sourceType, Long sourceId,
                              String question, String answer, int priority) {
        LiveKnowledgeSnapshot snapshot = new LiveKnowledgeSnapshot();
        snapshot.setTenantId(session.getTenantId());
        snapshot.setSessionId(session.getId());
        snapshot.setSourceType(sourceType);
        snapshot.setSourceId(sourceId);
        snapshot.setQuestion(question);
        snapshot.setAnswer(answer);
        snapshot.setPriority(priority);
        snapshots.save(snapshot);
    }

    private List<LiveSessionProduct> selectedProducts(Long id) {
        return sessionProducts.findBySessionIdOrderBySortOrderAscIdAsc(id);
    }

    private LiveDtos.View saveView(LiveSession session) {
        session.setUpdatedAt(Instant.now());
        return view(sessions.saveAndFlush(session));
    }

    private LiveDtos.View view(LiveSession session) {
        return new LiveDtos.View(session.getId(), session.getStoreId(), session.getName(), session.getRoomId(),
                session.getStatus(), session.getKnowledgeVersion(), session.getVersion(), session.getStartedAt(),
                session.getEndedAt(), selectedProducts(session.getId()).stream()
                        .map(LiveSessionProduct::getProductId).toList(),
                snapshots.findBySessionIdOrderByPriorityAscIdAsc(session.getId()).stream()
                        .map(snapshot -> new LiveDtos.SnapshotView(snapshot.getId(), snapshot.getSourceType(),
                                snapshot.getSourceId(), snapshot.getQuestion(), snapshot.getAnswer(),
                                snapshot.getPriority())).toList(), session.getConfig(), session.getAudioRoute(),
                session.getAudioTestStatus(), session.isPlayerPaired(), session.getPlayerLastHeartbeatAt());
    }

    private static void requireDraft(LiveSession session) {
        if (!"DRAFT".equals(session.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT, "直播开始后不能修改配置");
        }
    }

    private static void requireState(LiveSession session, Set<String> allowed) {
        if (!allowed.contains(session.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT, "当前状态不能执行该操作");
        }
    }

    private static void requireTarget(Long targetId) {
        if (targetId == null || targetId <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "持久化问答需要关联目标");
        }
    }

    private static String requiredText(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw BizException.of(ErrorCode.BAD_REQUEST, label + "不能为空且最多 " + maxLength + " 个字符");
        }
        return value.trim();
    }

    private static String roomId(String value) {
        return value == null || value.isBlank() ? null : requiredText(value, "直播间标识", 200);
    }

    private LiveDtos.QaView toQa(LiveSessionQa qa) {
        return new LiveDtos.QaView(qa.getId(), qa.getQuestion(), qa.getAnswer(), qa.getPersistMode(), qa.getTargetId(), qa.getVersion());
    }
}
