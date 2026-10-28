package com.wuyao.growth.copywriting;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.creative.image.ImageCreationService;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.product.ProductDtos;
import com.wuyao.growth.product.ProductService;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.store.StoreDtos;
import com.wuyao.growth.store.StoreService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CopyServiceTest {
    private final CopyBatchRepository batches = mock(CopyBatchRepository.class);
    private final CopyJournalRepository journal = mock(CopyJournalRepository.class);
    private final StoreAccessService storeAccess = mock(StoreAccessService.class);
    private final StoreService stores = mock(StoreService.class);
    private final ProductService products = mock(ProductService.class);
    private final AssetService assets = mock(AssetService.class);
    private final ImageCreationService images = mock(ImageCreationService.class);
    private final AccountService accounts = mock(AccountService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final BillingService billing = mock(BillingService.class);
    private final CopyService service = new CopyService(batches, journal, storeAccess, stores, products, assets, images,
            accounts, tasks, taskRepository, gateway, billing);

    private static CopyDtos.Create request(List<Long> images, List<Long> productIds, String note) {
        return new CopyDtos.Create("copy-key-1", 9L, "MOMENTS", "真诚日常", "LIGHT", true, images, List.of(), productIds, note, null);
    }

    private void configured() {
        when(gateway.configured(ModelAlias.TEXT_WRITER)).thenReturn(true);
        when(batches.findByRequestKey("copy-key-1")).thenReturn(Optional.empty());
        when(stores.profile(9L)).thenReturn(Optional.of(new StoreDtos.Profile(9L, "梧桐小馆", "", null, null, null, List.of(), List.of())));
    }

    private static CopyBatch succeeded() {
        var batch = new CopyBatch();
        batch.setId(7L); batch.setTenantId(3L); batch.setStoreId(9L); batch.setPlatform("MOMENTS"); batch.setStatus("SUCCEEDED");
        var versions = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 3; i++) {
            versions.add(new LinkedHashMap<>(Map.of("angle", "写法" + i, "title", "", "body", "第 " + i + " 条\n—— 写于〔店名〕",
                    "tags", List.of(), "aiScore", 2, "revision", 0)));
        }
        batch.setVersions(versions);
        return batch;
    }

    @Test void createReservesOneCreditSnapshotsProductsAndQueuesGeneration() {
        configured();
        when(products.get(4L, 1L)).thenReturn(new ProductDtos.View(4L, 9L, "招牌牛肉面", "PHYSICAL", "面", new BigDecimal("28.00"), "碗",
                null, "第二碗半价", null, "ACTIVE", null, null, 0L, null, null, List.of(), List.of()));
        when(billing.reserve(eq("copy-generate:copy-key-1"), eq("COPY"), eq(1L), anyString())).thenReturn("r1");
        when(batches.saveAndFlush(any())).thenAnswer(call -> { CopyBatch b = call.getArgument(0); b.setId(7L); return b; });
        var task = new Task(); task.setId(70L);
        when(tasks.submit(eq(CopyGenerateHandler.TYPE), eq("DEFAULT"), eq(Map.of("batchId", 7L)), eq("copy-generate-7"), eq(1L))).thenReturn(task);
        var view = TenantContext.runAs(3L, () -> service.create(request(List.of(5L), List.of(4L), " 今天下雨 "), 1L));
        assertThat(view.status()).isEqualTo("QUEUED");
        assertThat(view.note()).isEqualTo("今天下雨");
        assertThat(view.products()).containsExactly(new CopyDtos.ProductRef(4L, "招牌牛肉面"));
        assertThat(view.store()).containsEntry("店名", "梧桐小馆").doesNotContainKey("地址");
        verify(storeAccess).requireAccess(9L, 1L);
        verify(billing).requireMembership("COPY");
        verify(assets).imageReference(5L);
        var saved = org.mockito.ArgumentCaptor.forClass(CopyBatch.class);
        verify(batches).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getProductFacts()).isEqualTo("商品「招牌牛肉面」：价格 28 元/碗；优惠 第二碗半价");
    }

    @Test void somethingToWriteAboutIsRequiredBeforeBilling() {
        configured();
        assertThatThrownBy(() -> TenantContext.runAs(3L, () -> service.create(request(List.of(), List.of(), " "), 1L)))
                .isInstanceOf(BizException.class).hasMessageContaining("写一句");
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }

    @Test void unconfiguredModelIsRejectedBeforeBilling() {
        assertThatThrownBy(() -> service.create(request(List.of(5L), List.of(), null), 1L)).isInstanceOf(BizException.class);
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }

    @Test void retryingTheSameRequestReturnsTheExistingBatchAndADifferentOneConflicts() {
        configured();
        var prior = new CopyBatch(); prior.setId(7L); prior.setStoreId(9L); prior.setPlatform("MOMENTS"); prior.setStyle("真诚日常");
        prior.setStoreLevel("LIGHT"); prior.setVoiceOn(true); prior.setImageAssetIds(List.of(5L)); prior.setNote("今天下雨");
        when(batches.findByRequestKey("copy-key-1")).thenReturn(Optional.of(prior));
        assertThat(service.create(request(List.of(5L), List.of(), "今天下雨"), 1L).id()).isEqualTo(7L);
        assertThatThrownBy(() -> service.create(request(List.of(5L), List.of(), "换了一句"), 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("不同的文案");
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(tasks);
    }

    @Test void terminalGenerationFailureReleasesTheReservationOnce() {
        var batch = new CopyBatch(); batch.setId(7L); batch.setTaskId(70L); batch.setStatus("GENERATING"); batch.setBillingReservationId("r1");
        when(batches.lock(7L)).thenReturn(Optional.of(batch));
        var event = new TaskTerminalFailure(3L, 70L, CopyGenerateHandler.TYPE, Map.of("batchId", 7L));
        service.taskFailed(event);
        service.taskFailed(event);
        assertThat(batch.getStatus()).isEqualTo("FAILED");
        verify(billing).release(eq("r1"), eq("copy-generate:7:release"), anyString());
    }

    @Test void completionCapturesTheReservation() {
        var task = new Task(); task.setId(70L); task.setAttempts(1);
        when(taskRepository.findOwnedRunning(70L, 1)).thenReturn(Optional.of(task));
        var batch = new CopyBatch(); batch.setId(7L); batch.setTaskId(70L); batch.setStatus("GENERATING"); batch.setBillingReservationId("r1");
        when(batches.lock(7L)).thenReturn(Optional.of(batch));
        assertThat(service.completed(7L, task, List.of(Map.of("body", "a"), Map.of("body", "b")))).isTrue();
        assertThat(batch.getStatus()).isEqualTo("SUCCEEDED");
        verify(billing).capture(eq("r1"), eq(1L), eq("copy-generate:7:capture"), anyString());
    }

    @Test void rewriteMarksTheVersionAndCountsTowardsTheLimit() {
        when(gateway.configured(ModelAlias.TEXT_WRITER)).thenReturn(true);
        var batch = succeeded();
        when(batches.lock(7L)).thenReturn(Optional.of(batch));
        service.rewrite(7L, 1, new CopyDtos.Rewrite("SHORTER"), 1L);
        assertThat(batch.getVersions().get(1)).containsEntry("rewriting", "SHORTER");
        assertThat(batch.getRewriteCount()).isEqualTo(1);
        verify(tasks).submit(eq(CopyRewriteHandler.TYPE), eq("DEFAULT"),
                eq(Map.of("batchId", 7L, "index", 1, "revision", 0, "action", "SHORTER")), eq("copy-rewrite-7-1"), eq(1L));
        assertThatThrownBy(() -> service.rewrite(7L, 2, new CopyDtos.Rewrite("DE_AI"), 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("还没完成");
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }

    @Test void rewriteStopsAtTheLimit() {
        when(gateway.configured(ModelAlias.TEXT_WRITER)).thenReturn(true);
        var batch = succeeded(); batch.setRewriteCount(CopyService.REWRITE_LIMIT);
        when(batches.lock(7L)).thenReturn(Optional.of(batch));
        assertThatThrownBy(() -> service.rewrite(7L, 0, new CopyDtos.Rewrite("SHORTER"), 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("换一批");
    }

    @Test void rewriteResultOnlyLandsOnTheRevisionItWasAskedFor() {
        var batch = succeeded();
        batch.getVersions().get(1).put("rewriting", "SHORTER");
        batch.setRewriteCount(1);
        when(batches.lock(7L)).thenReturn(Optional.of(batch));
        var result = Map.<String, Object>of("title", "", "body", "短了", "tags", List.of(), "aiScore", 1, "aiReason", "口语");
        assertThat(service.rewritten(7L, 1, 3, result)).isFalse();
        assertThat(service.rewritten(7L, 1, 0, result)).isTrue();
        assertThat(batch.getVersions().get(1)).containsEntry("body", "短了").containsEntry("revision", 1).doesNotContainKey("rewriting");
    }

    @Test void failedRewriteKeepsTheTextAndGivesTheAttemptBack() {
        var batch = succeeded();
        batch.getVersions().get(0).put("rewriting", "DE_AI");
        batch.setRewriteCount(2);
        when(batches.lock(7L)).thenReturn(Optional.of(batch));
        service.rewriteFailed(7L, 0, 0, "改写没有通过检查");
        assertThat(batch.getVersions().get(0)).doesNotContainKey("rewriting").containsEntry("rewriteError", "改写没有通过检查")
                .containsEntry("body", "第 0 条\n—— 写于〔店名〕");
        assertThat(batch.getRewriteCount()).isEqualTo(1);
    }

    @Test void finalizingStoresTheTextAsPublishedAndIsIdempotent() {
        var batch = succeeded();
        when(batches.findById(7L)).thenReturn(Optional.of(batch));
        when(journal.findByBatchIdAndVersionIndex(7L, 2)).thenReturn(Optional.empty());
        when(stores.profile(9L)).thenReturn(Optional.of(new StoreDtos.Profile(9L, "梧桐小馆", null, null, null, null, List.of(), List.of())));
        when(journal.saveAndFlush(any())).thenAnswer(call -> { CopyJournalEntry e = call.getArgument(0); e.setId(11L); return e; });
        var entry = service.finalizeVersion(new CopyDtos.Finalize(7L, 2), 1L);
        assertThat(entry.body()).isEqualTo("第 2 条\n—— 写于梧桐小馆");
        assertThat(entry.title()).isNull();
        verify(storeAccess).requireAccess(9L, 1L);

        var existing = new CopyJournalEntry(); existing.setId(11L); existing.setBatchId(7L); existing.setVersionIndex(2); existing.setBody("已有");
        when(journal.findByBatchIdAndVersionIndex(7L, 2)).thenReturn(Optional.of(existing));
        assertThat(service.finalizeVersion(new CopyDtos.Finalize(7L, 2), 1L).body()).isEqualTo("已有");
        verify(journal, times(1)).saveAndFlush(any());
    }
}
