package com.wuyao.growth.live;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.knowledge.KnowledgeDtos;
import com.wuyao.growth.knowledge.KnowledgeService;
import com.wuyao.growth.product.ProductDtos;
import com.wuyao.growth.product.ProductService;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.voice.VoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveSessionServiceTest {

    private final LiveSessionRepository sessions = mock(LiveSessionRepository.class);
    private final LiveSessionProductRepository selected = mock(LiveSessionProductRepository.class);
    private final LiveSessionQaRepository qas = mock(LiveSessionQaRepository.class);
    private final LiveKnowledgeSnapshotRepository snapshots = mock(LiveKnowledgeSnapshotRepository.class);
    private final ProductService products = mock(ProductService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final StoreAccessService stores = mock(StoreAccessService.class);
    private final VoiceService voices = mock(VoiceService.class);
    private final LiveSessionService service = new LiveSessionService(
            sessions, selected, qas, snapshots, products, knowledge, stores, voices);
    private LiveSession session;

    @BeforeEach
    void setup() {
        session = new LiveSession();
        session.setId(20L);
        session.setTenantId(1L);
        session.setStoreId(2L);
        session.setCreatedBy(3L);
        session.setName("门店直播");
        session.setRoomId("room-123");
        session.setConfig(new LiveDtos.Config(null, null, null, null, null, null,
                List.of(new LiveDtos.VoiceRole("sample:7", "host"), new LiveDtos.VoiceRole("builtin:warm", "cohost"))));
        when(sessions.findForUpdate(20L)).thenReturn(Optional.of(session));
        when(sessions.findById(20L)).thenReturn(Optional.of(session));
        when(sessions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(qas.save(any())).thenAnswer(invocation -> {
            LiveSessionQa qa = invocation.getArgument(0);
            qa.setId(90L);
            return qa;
        });
    }

    @Test
    void listReturnsSavedSessionsAndRequiresStoreMembership() {
        when(sessions.findByStoreIdOrderByUpdatedAtDescIdDesc(2L)).thenReturn(List.of(session));

        assertThat(service.list(2L, 4L)).extracting(LiveDtos.Summary::id).containsExactly(20L);
        verify(stores).requireAccess(2L, 4L);

        doThrow(BizException.of(ErrorCode.FORBIDDEN, "无权限")).when(stores).requireAccess(2L, 9L);
        assertThatThrownBy(() -> service.list(2L, 9L)).isInstanceOf(BizException.class);
        verify(sessions, times(1)).findByStoreIdOrderByUpdatedAtDescIdDesc(2L);
    }

    @Test
    void omittedPersistModeSavesSessionQaWithoutRequiringTarget() {
        LiveDtos.QaView result = service.addQa(20L,
                new LiveDtos.QaRequest(" 营业时间？ ", " 每天九点营业。 ", null, null), 4L);

        assertThat(result.persistMode()).isEqualTo("SESSION");
        assertThat(result.question()).isEqualTo("营业时间？");
        verifyNoInteractions(products, knowledge, snapshots);
    }

    @Test
    void productPersistenceWritesThroughProductServiceAndRetainsSessionOverride() {
        selectProduct(30L);

        service.addQa(20L, new LiveDtos.QaRequest("保质期？", "六个月。", "PRODUCT_FAQ", 30L), 4L);

        verify(products).createFaq(30L, new ProductDtos.FaqRequest("保质期？", "六个月。", null), 4L);
        ArgumentCaptor<LiveSessionQa> saved = ArgumentCaptor.forClass(LiveSessionQa.class);
        verify(qas).save(saved.capture());
        assertThat(saved.getValue().getTargetId()).isEqualTo(30L);
        assertThat(saved.getValue().getPersistMode()).isEqualTo("PRODUCT_FAQ");
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(4L);
    }

    @Test
    void productPersistenceRejectsUnselectedOrOtherStoreProductsBeforeWriting() {
        assertThatThrownBy(() -> service.addQa(20L,
                new LiveDtos.QaRequest("问题", "回答", "PRODUCT_FAQ", 30L), 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("本场");
        selectProduct(30L);
        when(products.get(30L, 4L)).thenReturn(product(30L, 99L));
        assertThatThrownBy(() -> service.addQa(20L,
                new LiveDtos.QaRequest("问题", "回答", "PRODUCT_FAQ", 30L), 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("当前门店");
        verify(products, never()).createFaq(anyLong(), any(), anyLong());
        verify(qas, never()).save(any());
    }

    @Test
    void storeKnowledgeIsSavedAsDraftInAnExplicitSameStoreFaqSet() {
        when(knowledge.getSet(40L, 4L)).thenReturn(knowledgeSet(2L, "FAQ", "PUBLISHED"));

        service.addQa(20L, new LiveDtos.QaRequest("停车？", "可免费停车。", "STORE_KNOWLEDGE", 40L), 4L);

        verify(knowledge).createEntry(40L, new KnowledgeDtos.CreateEntryRequest(
                "停车？", "可免费停车。", "STORE", null, "LIVE_DRAFT", "DRAFT"), 4L);
        verify(knowledge, never()).publish(anyLong(), anyLong());
        verify(qas).save(any());
    }

    @Test
    void storeKnowledgeRejectsOtherStoreAndArchivedTargetsBeforeWriting() {
        when(knowledge.getSet(40L, 4L)).thenReturn(knowledgeSet(99L, "FAQ", "PUBLISHED"));
        var request = new LiveDtos.QaRequest("停车？", "可免费停车。", "STORE_KNOWLEDGE", 40L);
        assertThatThrownBy(() -> service.addQa(20L, request, 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("当前门店");

        when(knowledge.getSet(40L, 4L)).thenReturn(knowledgeSet(2L, "FAQ", "ARCHIVED"));
        assertThatThrownBy(() -> service.addQa(20L, request, 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("未归档");
        verify(knowledge, never()).createEntry(anyLong(), any(), anyLong());
        verify(qas, never()).save(any());
    }

    @Test
    void startCapturesSessionProductAndStoreAnswersWithCorrectProvenanceAndCurrentUser() {
        selectProduct(30L);
        LiveSessionQa local = new LiveSessionQa();
        local.setId(90L);
        local.setQuestion("发货？");
        local.setAnswer("当天发货。");
        when(qas.findBySessionIdOrderByIdAsc(20L)).thenReturn(List.of(local));
        String longQuestion = "问".repeat(1000);
        when(products.listFaqs(30L, 4L)).thenReturn(List.of(
                new ProductDtos.FaqListView(60L, 30L, longQuestion, "商品答案", "ACTIVE", 0, 0L, null, null),
                new ProductDtos.FaqListView(61L, 30L, "停用问题", "停用答案", "DISABLED", 1, 0L, null, null)));
        when(knowledge.context(2L, List.of(30L), 4L)).thenReturn(new KnowledgeDtos.KnowledgeContext(
                2L, 7, List.of(entry(70L, "PRODUCT", 30L), entry(80L, "STORE", null)), Instant.now()));

        LiveDtos.View result = service.start(20L, 4L);

        assertThat(result.status()).isEqualTo("LIVE");
        assertThat(result.startedAt()).isNotNull();
        assertThat(result.knowledgeVersion()).isEqualTo(7);
        verify(knowledge).context(2L, List.of(30L), 4L);
        ArgumentCaptor<LiveKnowledgeSnapshot> captured = ArgumentCaptor.forClass(LiveKnowledgeSnapshot.class);
        verify(snapshots, times(4)).save(captured.capture());
        assertThat(captured.getAllValues()).extracting(LiveKnowledgeSnapshot::getPriority)
                .containsExactly(1, 2, 2, 3);
        assertThat(captured.getAllValues()).extracting(LiveKnowledgeSnapshot::getSourceId)
                .containsExactly(90L, 60L, 70L, 80L);
        assertThat(captured.getAllValues().get(1).getQuestion()).isEqualTo(longQuestion);
    }

    @Test
    void startRevalidatesDeletedProductsAndDoesNotPartiallyReplaceSnapshot() {
        selectProduct(30L);
        when(products.get(30L, 4L)).thenThrow(BizException.of(ErrorCode.NOT_FOUND, "商品不存在"));

        assertThatThrownBy(() -> service.start(20L, 4L)).isInstanceOf(BizException.class);
        assertThat(session.getStatus()).isEqualTo("DRAFT");
        verifyNoInteractions(snapshots, knowledge);
    }

    @Test
    void startRequiresAtLeastOneProduct() {
        assertThatThrownBy(() -> service.start(20L, 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("至少选择一个商品");
        verifyNoInteractions(snapshots, knowledge);
    }

    @Test
    void startRequiresAHostVoiceThatCanActuallySpeakBecauseConfigurationFreezesAfterwards() {
        selectProduct(30L);
        session.setConfig(new LiveDtos.Config(null, null, null, null, null, null,
                List.of(new LiveDtos.VoiceRole("builtin:warm", "cohost"), new LiveDtos.VoiceRole("demo-voice", "host"))));
        assertThatThrownBy(() -> service.start(20L, 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("请先选择本场的主播音色");

        session.setConfig(new LiveDtos.Config(null, null, null, null, null, null,
                List.of(new LiveDtos.VoiceRole("sample:7", "host"), new LiveDtos.VoiceRole("builtin:warm", "cohost"))));
        when(voices.resolveVoice(2L, null, "warm", 4L))
                .thenThrow(BizException.of(ErrorCode.BAD_REQUEST, "请选择可用的系统音色"));
        assertThatThrownBy(() -> service.start(20L, 4L)).hasMessageContaining("系统音色");

        assertThat(session.getStatus()).isEqualTo("DRAFT");
        verify(voices).resolveVoice(2L, 7L, null, 4L);
        verifyNoInteractions(snapshots, knowledge);
    }

    @Test
    void aStoreCannotHaveASecondSessionOnAir() {
        selectProduct(30L);
        LiveSession running = new LiveSession();
        running.setId(19L);
        running.setName("上午场");
        running.setStatus("PAUSED");
        when(sessions.findByStoreIdAndStatusIn(2L, LiveSessionService.ACTIVE)).thenReturn(List.of(running));

        assertThatThrownBy(() -> service.start(20L, 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("上午场").hasMessageContaining("请先结束");

        assertThat(session.getStatus()).isEqualTo("DRAFT");
        verifyNoInteractions(snapshots);
    }

    @Test
    void onlyASessionThatNeverStartedCanBeDeleted() {
        service.delete(20L, 4L);
        verify(sessions).delete(session);

        session.setStatus("ENDED");
        assertThatThrownBy(() -> service.delete(20L, 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("还没开始");
        verify(sessions, times(1)).delete(any(LiveSession.class));
    }

    @Test
    void theNextSessionCarriesOverVoicesProductsAndAnswersButNotWhatLeftTheLibrary() {
        session.setStatus("ENDED");
        LiveSessionProduct kept = new LiveSessionProduct();
        kept.setProductId(30L);
        LiveSessionProduct gone = new LiveSessionProduct();
        gone.setProductId(31L);
        when(selected.findBySessionIdOrderBySortOrderAscIdAsc(20L)).thenReturn(List.of(kept, gone));
        when(products.get(30L, 4L)).thenReturn(product(30L, 2L));
        when(products.get(31L, 4L)).thenThrow(BizException.of(ErrorCode.NOT_FOUND, "商品不存在"));
        when(qas.findBySessionIdOrderByIdAsc(20L)).thenReturn(List.of(
                qa("营业时间", null, "SESSION"), qa("保质期", 30L, "PRODUCT_FAQ"),
                qa("已下架商品的问题", 31L, "SESSION"), qa("停车", 88L, "STORE_KNOWLEDGE")));
        when(sessions.save(any())).thenAnswer(invocation -> {
            LiveSession saved = invocation.getArgument(0);
            saved.setId(21L);
            return saved;
        });

        LiveDtos.View copy = service.duplicate(20L, new LiveDtos.DuplicateRequest("10月5日直播"), 4L);

        assertThat(copy.id()).isEqualTo(21L);
        assertThat(copy.status()).isEqualTo("DRAFT");
        assertThat(copy.name()).isEqualTo("10月5日直播");
        assertThat(copy.config()).isEqualTo(session.getConfig());
        ArgumentCaptor<LiveSessionProduct> products = ArgumentCaptor.forClass(LiveSessionProduct.class);
        verify(selected).save(products.capture());
        assertThat(products.getValue().getProductId()).isEqualTo(30L);
        assertThat(products.getValue().getSessionId()).isEqualTo(21L);
        ArgumentCaptor<LiveSessionQa> answers = ArgumentCaptor.forClass(LiveSessionQa.class);
        verify(qas, times(3)).save(answers.capture());
        assertThat(answers.getAllValues()).extracting(LiveSessionQa::getQuestion)
                .containsExactly("营业时间", "保质期", "停车");
        assertThat(answers.getAllValues()).allSatisfy(answer -> assertThat(answer.getSessionId()).isEqualTo(21L));
        // Copying a session must not file its answers into the product or store libraries a second time.
        verify(this.products, never()).createFaq(anyLong(), any(), anyLong());
        verifyNoInteractions(knowledge);
        // The source is history and stays exactly as it was.
        assertThat(session.getStatus()).isEqualTo("ENDED");
    }

    @Test
    void startDoesNotRequireARoomLink() {
        session.setRoomId(null);
        selectProduct(30L);
        when(knowledge.context(2L, List.of(30L), 4L)).thenReturn(new KnowledgeDtos.KnowledgeContext(
                2L, 1, List.of(), Instant.now()));

        assertThat(service.start(20L, 4L).status()).isEqualTo("LIVE");
        assertThat(session.getRoomId()).isNull();
    }

    @Test
    void resumePreservesStartedAtAndCapturedKnowledgeWhileStartOnPausedIsRejected() {
        Instant start = Instant.parse("2026-09-28T01:00:00Z");
        session.setStatus("PAUSED");
        session.setStartedAt(start);
        session.setKnowledgeVersion(5);
        assertThatThrownBy(() -> service.start(20L, 4L)).isInstanceOf(BizException.class);

        LiveDtos.View resumed = service.resume(20L, 4L);

        assertThat(resumed.status()).isEqualTo("LIVE");
        assertThat(resumed.startedAt()).isEqualTo(start);
        assertThat(resumed.knowledgeVersion()).isEqualTo(5);
        verifyNoInteractions(products, knowledge);
        verify(snapshots, never()).deleteBySessionId(anyLong());
        verify(snapshots, never()).save(any());
    }

    @Test
    void liveQaAppendsOnlySessionOverrideAndEndedSessionRejectsNewQa() {
        session.setStatus("LIVE");
        var request = new LiveDtos.QaRequest("停车？", "可免费停车。", "SESSION", null);
        service.addQa(20L, request, 4L);

        ArgumentCaptor<LiveKnowledgeSnapshot> captured = ArgumentCaptor.forClass(LiveKnowledgeSnapshot.class);
        verify(snapshots).save(captured.capture());
        assertThat(captured.getValue().getSourceType()).isEqualTo("SESSION");
        assertThat(captured.getValue().getSourceId()).isEqualTo(90L);
        assertThat(captured.getValue().getPriority()).isEqualTo(1);
        verifyNoInteractions(products, knowledge);
        verify(snapshots, never()).deleteBySessionId(anyLong());

        session.setStatus("ENDED");
        assertThatThrownBy(() -> service.addQa(20L, request, 4L)).isInstanceOf(BizException.class);
        verify(qas, times(1)).save(any());
    }

    @Test
    void pauseResumeEndRespectStateMachineAndEndedSessionsCannotRestart() {
        assertThatThrownBy(() -> service.pause(20L, 4L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.end(20L, 4L)).isInstanceOf(BizException.class);
        session.setStatus("LIVE");
        assertThat(service.pause(20L, 4L).status()).isEqualTo("PAUSED");
        assertThatThrownBy(() -> service.pause(20L, 4L)).isInstanceOf(BizException.class);
        assertThat(service.end(20L, 4L).endedAt()).isNotNull();
        assertThat(session.getStatus()).isEqualTo("ENDED");
        assertThatThrownBy(() -> service.resume(20L, 4L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.start(20L, 4L)).isInstanceOf(BizException.class);
    }

    @Test
    void roomAndProductConfigurationCannotBeChangedAfterStart() {
        session.setStatus("LIVE");
        assertThatThrownBy(() -> service.parseLink(20L, "new-room", 4L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.update(20L,
                new LiveDtos.UpdateRequest("新名称", null, List.of(), null), 4L)).isInstanceOf(BizException.class);
        verify(selected, never()).deleteBySessionId(anyLong());
        assertThat(session.getRoomId()).isEqualTo("room-123");
    }

    @Test
    void audioRouteCanBeChangedOnlyWhileDraft() {
        LiveDtos.View updated = service.updateAudioRoute(20L,
                new LiveDtos.AudioRouteRequest("speaker_pickup", "confirmed"), 4L);

        assertThat(session.getAudioRoute()).isEqualTo("speaker_pickup");
        assertThat(session.getAudioTestStatus()).isEqualTo("confirmed");
        assertThat(updated.audioRoute()).isEqualTo("speaker_pickup");
        assertThat(updated.audioTestStatus()).isEqualTo("confirmed");

        session.setStatus("LIVE");
        assertThatThrownBy(() -> service.updateAudioRoute(20L,
                new LiveDtos.AudioRouteRequest("pc_soundcard", null), 4L))
                .isInstanceOf(BizException.class);
    }

    @Test
    void productReplacementValidatesAllProductsBeforeDeletingExistingSelections() {
        when(products.get(30L, 4L)).thenReturn(product(30L, 2L));
        when(products.get(31L, 4L)).thenReturn(product(31L, 99L));
        assertThatThrownBy(() -> service.update(20L,
                new LiveDtos.UpdateRequest(null, null, List.of(30L, 31L), null), 4L))
                .isInstanceOf(BizException.class);
        verify(selected, never()).deleteBySessionId(anyLong());
    }

    @Test
    void createDeduplicatesProductsPreservingOrderAndUsesAuthenticatedTenant() {
        when(sessions.save(any())).thenAnswer(invocation -> {
            LiveSession created = invocation.getArgument(0);
            created.setId(21L);
            return created;
        });
        when(products.get(30L, 4L)).thenReturn(product(30L, 2L));
        when(products.get(31L, 4L)).thenReturn(product(31L, 2L));

        TenantContext.runAs(1L, () -> service.create(2L,
                new LiveDtos.CreateRequest("新直播", null, List.of(31L, 30L, 31L)), 4L));

        ArgumentCaptor<LiveSessionProduct> captured = ArgumentCaptor.forClass(LiveSessionProduct.class);
        verify(selected, times(2)).save(captured.capture());
        assertThat(captured.getAllValues()).extracting(LiveSessionProduct::getProductId).containsExactly(31L, 30L);
        assertThat(captured.getAllValues()).extracting(LiveSessionProduct::getSortOrder).containsExactly(0, 1);
        assertThat(captured.getAllValues()).allSatisfy(product -> assertThat(product.getTenantId()).isEqualTo(1L));
    }

    @Test
    void draftConfigurationSurvivesSaveAndStaleVersionCannotOverwriteIt() {
        var config = new LiveDtos.Config(new LiveDtos.Tone("直接报价", List.of("顾虑问题拆解"),
                List.of("核心特点讲解", "售后与保障")), 2.75, false, 4, false, List.of("v1"), List.of());
        var saved = service.update(20L, new LiveDtos.UpdateRequest("配置草稿", "new-room", null, 0L, config), 4L);
        assertThat(saved.config()).isEqualTo(config);
        assertThat(saved.roomId()).isEqualTo("new-room");

        session.setVersion(1L);
        assertThatThrownBy(() -> service.update(20L,
                new LiveDtos.UpdateRequest("过期覆盖", null, null, 0L, null), 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("已被修改");
        assertThat(session.getConfig()).isEqualTo(config);
        assertThat(session.getName()).isEqualTo("配置草稿");
    }

    @Test
    void editingPersistedQaChangesOnlySessionCopyAndRequiresItsVersion() {
        LiveSessionQa qa = existingQa();
        qa.setPersistMode("PRODUCT_FAQ");
        qa.setTargetId(30L);
        when(qas.findById(90L)).thenReturn(Optional.of(qa));
        when(qas.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.updateQa(20L, 90L,
                new LiveDtos.QaUpdateRequest("问题", "过期回答", 1L), 4L))
                .isInstanceOf(BizException.class).hasMessageContaining("已被修改");
        var result = service.updateQa(20L, 90L, new LiveDtos.QaUpdateRequest(" 新问题 ", " 新回答 ", 0L), 4L);
        assertThat(result.question()).isEqualTo("新问题");
        assertThat(result.answer()).isEqualTo("新回答");
        assertThat(result.persistMode()).isEqualTo("PRODUCT_FAQ");
        verifyNoInteractions(products, knowledge, snapshots);
    }

    @Test
    void qaEditAndDeleteRejectOtherSessionRowsAndStartedSessions() {
        LiveSessionQa qa = existingQa();
        qa.setSessionId(99L);
        when(qas.findById(90L)).thenReturn(Optional.of(qa));
        assertThatThrownBy(() -> service.updateQa(20L, 90L,
                new LiveDtos.QaUpdateRequest("问题", "回答", 0L), 4L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.deleteQa(20L, 90L, 0L, 4L)).isInstanceOf(BizException.class);
        qa.setSessionId(20L);
        session.setStatus("LIVE");
        assertThatThrownBy(() -> service.deleteQa(20L, 90L, 0L, 4L)).isInstanceOf(BizException.class);
        verify(qas, never()).delete(any());
        verify(qas, never()).saveAndFlush(any());
    }

    @Test
    void deletingQaRequiresCurrentVersionAndPreservesLibraryCopies() {
        LiveSessionQa qa = existingQa();
        when(qas.findById(90L)).thenReturn(Optional.of(qa));
        assertThatThrownBy(() -> service.deleteQa(20L, 90L, null, 4L)).isInstanceOf(BizException.class);
        service.deleteQa(20L, 90L, 0L, 4L);
        verify(qas).delete(qa);
        verifyNoInteractions(products, knowledge, snapshots);
    }

    @Test
    void removedProductOverridesDoNotEnterLiveKnowledgeSnapshot() {
        selectProduct(30L);
        LiveSessionQa qa = existingQa();
        qa.setTargetId(31L);
        when(qas.findBySessionIdOrderByIdAsc(20L)).thenReturn(List.of(qa));
        when(knowledge.context(2L, List.of(30L), 4L)).thenReturn(
                new KnowledgeDtos.KnowledgeContext(2L, 1, List.of(), Instant.now()));
        service.start(20L, 4L);
        verify(snapshots, never()).save(any());
    }

    private LiveSessionQa existingQa() {
        LiveSessionQa qa = new LiveSessionQa();
        qa.setId(90L);
        qa.setSessionId(20L);
        qa.setQuestion("问题");
        qa.setAnswer("回答");
        return qa;
    }

    private static LiveSessionQa qa(String question, Long targetId, String persistMode) {
        LiveSessionQa qa = new LiveSessionQa();
        qa.setQuestion(question);
        qa.setAnswer("回答");
        qa.setTargetId(targetId);
        qa.setPersistMode(persistMode);
        return qa;
    }

    private void selectProduct(Long id) {
        LiveSessionProduct product = new LiveSessionProduct();
        product.setSessionId(20L);
        product.setProductId(id);
        when(selected.findBySessionIdOrderBySortOrderAscIdAsc(20L)).thenReturn(List.of(product));
        when(products.get(id, 4L)).thenReturn(product(id, 2L));
    }

    private ProductDtos.View product(Long id, Long storeId) {
        return new ProductDtos.View(id, storeId, "茶叶", "PHYSICAL", "食品", BigDecimal.TEN,
                "罐", null, null, null, "ACTIVE", 0L, null, null, List.of(), List.of());
    }

    private KnowledgeDtos.SetView knowledgeSet(Long storeId, String kind, String status) {
        return new KnowledgeDtos.SetView(40L, storeId, "门店规则", kind, status, null, 1, null, null, null);
    }

    private KnowledgeDtos.EntryView entry(Long id, String scope, Long productId) {
        return new KnowledgeDtos.EntryView(id, 40L, 2L, productId, scope, "MANUAL", "ACTIVE",
                "问题" + id, "回答" + id, 0L, 0, 1, null, null);
    }
}
