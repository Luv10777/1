package com.wuyao.growth.creative.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ImageModelProperties;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskTerminalFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImageCreationServiceDownloadTest {
    @Test
    void visiblePreviewSettlesOnceBeforeStorageAndPersistenceDoesNotChargeAgain() {
        var fixture = new BillingFixture();

        assertThat(fixture.service.recordPreviewLoaded(7L, 8L, 0)).isTrue();
        var previewedAt = fixture.item.getPreviewedAt();
        assertThat(previewedAt).isNotNull();
        assertThat(fixture.item.isBillingHeld()).isFalse();
        assertThat(fixture.item.getPersistedAt()).isNull();
        assertThat(fixture.item.getStatus()).isEqualTo("GENERATING");
        var order = inOrder(fixture.billing, fixture.creations, fixture.items);
        order.verify(fixture.billing).lockForGeneration();
        order.verify(fixture.creations).lock(7L);
        order.verify(fixture.items).lock(8L);

        fixture.service.recordPreviewLoaded(7L, 8L, 0);
        fixture.service.markImagePersisted(8L, fixture.task, fixture.storageKey, 2048, 2048, null);

        assertThat(fixture.item.getPreviewedAt()).isEqualTo(previewedAt);
        verify(fixture.billing).capture("reservation", 3, "image-delivered:8:0", "图片成功交付 · 1 张");
        verify(fixture.billing, never()).releaseAmount(anyString(), anyLong(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void downloadFailureOnlyRefundsImagesWithoutAVisiblePreview(boolean visible) {
        var fixture = new BillingFixture();
        if (visible) fixture.service.recordPreviewLoaded(7L, 8L, 0);

        fixture.service.taskFailed(fixture.failure());
        fixture.service.taskFailed(fixture.failure());

        assertThat(fixture.item.getStatus()).isEqualTo("INTERRUPTED");
        assertThat(fixture.item.isBillingHeld()).isFalse();
        if (visible) {
            verify(fixture.billing).capture("reservation", 3, "image-delivered:8:0", "图片成功交付 · 1 张");
            verify(fixture.billing, never()).releaseAmount(anyString(), anyLong(), anyString(), anyString());
            assertThat(fixture.item.getError()).doesNotContain("退回");
        } else {
            verify(fixture.billing).releaseAmount("reservation", 3, "image-task-failed:12", "图片未交付 · 退回 3 烟火");
            verify(fixture.billing, never()).capture(anyString(), anyLong(), anyString(), anyString());
        }
    }

    @Test
    void cancellationSettlesPreviouslyRecordedPreviewsBeforeReleasingTheRemainingReservation() {
        var fixture = new BillingFixture();
        fixture.item.setPreviewedAt(Instant.now());

        fixture.service.cancel(7L);

        var order = inOrder(fixture.billing);
        order.verify(fixture.billing).capture("reservation", 3, "image-delivered:8:0", "图片成功交付 · 1 张");
        order.verify(fixture.billing).release("reservation", "image-terminal:7:0", "图片创作结束 · 退回未交付烟火");
        assertThat(fixture.item.isBillingHeld()).isFalse();
    }

    @Test
    void failureRecoverySettlesPreviouslyRecordedPreviewsInsteadOfRefundingThem() {
        var fixture = new BillingFixture();
        fixture.item.setPreviewedAt(Instant.now());

        fixture.service.taskFailed(fixture.failure());

        verify(fixture.billing).capture("reservation", 3, "image-delivered:8:0", "图片成功交付 · 1 张");
        verify(fixture.billing, never()).releaseAmount(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void retryingStorageForAnAlreadyVisibleImageDoesNotReserveOrChargeAgain() {
        var fixture = new BillingFixture();
        fixture.service.recordPreviewLoaded(7L, 8L, 0);
        fixture.service.taskFailed(fixture.failure());
        var retryTask = new Task(); retryTask.setId(13L);
        when(fixture.tasks.submit(anyString(), anyString(), anyMap(), anyString(), isNull())).thenReturn(retryTask);
        when(fixture.tasks.ownsExecution(retryTask)).thenReturn(true);

        fixture.service.retry(7L, 8L, 12L, null);
        fixture.service.markImagePersisted(8L, retryTask, fixture.storageKey, 2048, 2048, null);

        assertThat(fixture.item.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(fixture.item.getGeneration()).isZero();
        verify(fixture.billing, never()).reserveMore(anyString(), anyLong(), anyString(), anyString());
        verify(fixture.billing).capture("reservation", 3, "image-delivered:8:0", "图片成功交付 · 1 张");
    }

    @Test
    void staleGenerationsAndReusedImagesCannotTriggerPreviewBilling() {
        var fixture = new BillingFixture();
        assertThat(fixture.service.recordPreviewLoaded(7L, 8L, 1)).isFalse();
        fixture.item.setTaskId(null);
        assertThat(fixture.service.recordPreviewLoaded(7L, 8L, 0)).isFalse();
        fixture.item.setTaskId(12L);
        fixture.item.setProviderImageUrl(null);
        assertThat(fixture.service.recordPreviewLoaded(7L, 8L, 0)).isFalse();

        assertThat(fixture.item.getPreviewedAt()).isNull();
        verify(fixture.billing, never()).capture(anyString(), anyLong(), anyString(), anyString());
    }

    private static class BillingFixture {
        final ImageCreationRepository creations = mock(ImageCreationRepository.class);
        final ImageItemRepository items = mock(ImageItemRepository.class);
        final BillingService billing = mock(BillingService.class);
        final TaskService tasks = mock(TaskService.class);
        final ImageCreation creation = new ImageCreation();
        final ImageItem item = new ImageItem();
        final Task task = new Task();
        final String storageKey = "t2/generated/8/0/background.png";
        final ImageCreationService service = new ImageCreationService(creations, items, mock(AssetService.class),
            tasks, mock(ObjectStorage.class), mock(AiGateway.class), new ImageModelProperties(),
            new ObjectMapper(), mock(TenantRateLimiter.class), billing);

        BillingFixture() {
            creation.setId(7L); creation.setTenantId(2L); creation.setStatus("GENERATING");
            creation.setBillingReservationId("reservation");
            creation.setConcurrencyPermitHeld(true);
            creation.setRequest(new ImageDtos.Create("request", "PRODUCT_SET", "product", List.of(),
                "1:1", "2K", 1, "LOCAL", "PRODUCT_MAIN", "test", "test", null));
            item.setId(8L); item.setTenantId(2L); item.setCreationId(7L);
            item.setStatus("GENERATING"); item.setTaskId(12L); item.setBillingHeld(true);
            item.setProviderImageUrl("https://provider.example/image.png");
            item.setSpec(new ImageDtos.Spec("product", "product photo", "", ""));
            task.setId(12L);
            when(creations.findById(7L)).thenReturn(Optional.of(creation));
            when(creations.lock(7L)).thenReturn(Optional.of(creation));
            when(items.creationId(8L)).thenReturn(Optional.of(7L));
            when(items.lock(8L)).thenReturn(Optional.of(item));
            when(items.findByCreationIdOrderByOrdinal(7L)).thenReturn(List.of(item));
            when(tasks.ownsExecution(task)).thenReturn(true);
        }

        TaskTerminalFailure failure() {
            return new TaskTerminalFailure(2L, 12L, "IMAGE_DOWNLOAD", Map.of("itemId", 8L, "targetKey", storageKey));
        }
    }

    @Test
    void downloadedImagesCompleteTheCreationAndReleaseItsPermit() {
        var creations = mock(ImageCreationRepository.class);
        var items = mock(ImageItemRepository.class);
        var limiter = mock(TenantRateLimiter.class);
        var tasks = mock(TaskService.class);
        var execution = new Task(); execution.setId(12L);
        when(tasks.ownsExecution(execution)).thenReturn(true);
        var service = new ImageCreationService(creations, items, mock(AssetService.class),
            tasks, mock(ObjectStorage.class), mock(AiGateway.class),
            new ImageModelProperties(), new ObjectMapper(), limiter,mock(com.wuyao.growth.billing.BillingService.class));

        var creation = new ImageCreation();
        creation.setId(7L);
        creation.setTenantId(2L);
        creation.setStatus("GENERATING");
        creation.setConcurrencyPermitHeld(true);
        var first = new ImageItem();
        first.setId(8L);
        first.setCreationId(7L);
        first.setStatus("GENERATING");
        first.setTaskId(12L);
        var second = new ImageItem();
        second.setId(9L);
        second.setCreationId(7L);
        second.setStatus("GENERATING");
        second.setTaskId(12L);
        when(creations.findById(7L)).thenReturn(Optional.of(creation));
        when(creations.lock(7L)).thenReturn(Optional.of(creation));
        when(items.creationId(anyLong())).thenReturn(Optional.of(7L));
        when(items.lock(8L)).thenReturn(Optional.of(first));
        when(items.lock(9L)).thenReturn(Optional.of(second));
        when(items.findByCreationIdOrderByOrdinal(7L)).thenReturn(List.of(first, second));
        when(creations.clearConcurrencyPermit(7L)).thenReturn(1, 0);

        service.markImagePersisted(8L, execution, "t2/generated/8/0/background.png", 2048, 2048, null);
        assertThat(first.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(creation.getStatus()).isEqualTo("GENERATING");
        verifyNoInteractions(limiter);

        service.markImagePersisted(9L, execution, "t2/generated/9/0/background.png", 2048, 2048, null);
        assertThat(second.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(creation.getStatus()).isEqualTo("SUCCEEDED");
        verify(limiter).releaseImageGeneration(2L);
    }
}
