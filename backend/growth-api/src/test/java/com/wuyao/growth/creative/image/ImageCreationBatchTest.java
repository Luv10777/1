package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 灵感绘图一次出多张（新手版对话页用），以及记录里带回当初的参考图。 */
class ImageCreationBatchTest {
    private static final Long TENANT_ID = 41L;
    private final ImageCreationRepository creations = mock(ImageCreationRepository.class);
    private final ImageItemRepository items = mock(ImageItemRepository.class);
    private final AssetService assets = mock(AssetService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TenantRateLimiter limiter = mock(TenantRateLimiter.class);
    private final BillingService billing = mock(BillingService.class);
    private final TransactionTemplate transactions = new TransactionTemplate(new TestTransactionManager());
    private ImageCreationService service;

    @BeforeEach
    void setUp() {
        when(creations.findByRequestKey(anyString())).thenReturn(Optional.empty());
        when(creations.saveAndFlush(any(ImageCreation.class))).thenAnswer(invocation -> {
            ImageCreation creation = invocation.getArgument(0);
            creation.setId(12L);
            when(creations.findById(12L)).thenReturn(Optional.of(creation));
            return creation;
        });
        when(items.findByCreationIdOrderByOrdinal(12L)).thenReturn(List.of());
        when(tasks.submit(anyString(), anyString(), anyMap(), anyString(), any())).thenAnswer(invocation -> {
            var task = new Task();
            task.setId(30L);
            return task;
        });
        when(limiter.tryAcquireImageGeneration(TENANT_ID, 20, 200)).thenReturn(true);
        when(assets.imageReference(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return new AssetService.ImageReference(id, "t41/image/" + id, "ref.png");
        });
        when(assets.imagePreview(1L)).thenReturn(Optional.of(new AssetService.ImagePreview(1L, "咖啡.png", "https://storage.test/1")));
        when(assets.imagePreview(2L)).thenReturn(Optional.empty());
        var gateway = mock(AiGateway.class);
        when(gateway.configured(any())).thenReturn(true);
        service = new ImageCreationService(creations, items, assets, tasks, mock(ObjectStorage.class), gateway,
                new ImageModelProperties(), new ObjectMapper(), limiter, billing);
        ReflectionTestUtils.setField(service, "tenantMaxConcurrent", 20);
        ReflectionTestUtils.setField(service, "globalMaxConcurrent", 200);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void posterAcceptsABatchAndReservesCreditsForEveryImage() {
        var view = transactions.execute(status -> service.create(request(4), 7L));

        assertThat(view.workflow()).isEqualTo("POSTER");
        assertThat(view.count()).isEqualTo(4);
        verify(billing).reserve(eq("image-create:batch-request-1"), eq("IMAGE"), eq(4L * BillingService.IMAGE_CREDITS), anyString());
    }

    @Test
    void theRecordCarriesItsReferencesEvenWhenAFileIsGone() {
        var view = transactions.execute(status -> service.create(request(1), 7L));

        assertThat(view.references()).containsExactly(
                new ImageDtos.ReferenceView(1L, "SUBJECT", "咖啡.png", "https://storage.test/1"),
                new ImageDtos.ReferenceView(2L, "STYLE", "参考图已不可用", null));
    }

    @Test
    void thePosterPlannerIsToldToVaryABatch() {
        assertThat(ImagePlanner.system("POSTER")).contains("request.count").contains("items 数量必须正好等于它");
    }

    private ImageDtos.Create request(int count) {
        return new ImageDtos.Create("batch-request-1", "POSTER", "一杯手冲咖啡",
                List.of(new ImageDtos.Reference(1L, "SUBJECT"), new ImageDtos.Reference(2L, "STYLE")),
                "3:4", "1K", count, "LOCAL", "POSTER", "", "帮我搭配", null);
    }

    // 生成许可挂在事务同步上，仓库仍是 mock。
    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
