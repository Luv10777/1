package com.wuyao.growth.creative.inspiration;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageInspirationServiceTest {
    private final ImageInspirationRepository inspirations = mock(ImageInspirationRepository.class);
    private final AssetService assets = mock(AssetService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final BillingService billing = mock(BillingService.class);
    private final ImageInspirationService service = new ImageInspirationService(inspirations, assets, tasks, taskRepository, gateway, billing);

    private static ImageInspirationDtos.Create request(Long previous) {
        return new ImageInspirationDtos.Create("inspiration-key-1", List.of(5L), "写实摄影", "新品上新", previous);
    }

    @Test void createReservesOneCreditUnderItsOwnCapabilityAndQueuesGeneration() {
        when(gateway.configured(ModelAlias.IMAGE_INSPIRATION_PLANNER)).thenReturn(true);
        when(inspirations.findByRequestKey("inspiration-key-1")).thenReturn(Optional.empty());
        when(billing.reserve(eq("image-inspiration:inspiration-key-1"), eq("IMAGE_INSPIRATION"), eq((long) BillingService.IMAGE_INSPIRATION_CREDITS), anyString())).thenReturn("r1");
        when(inspirations.saveAndFlush(any())).thenAnswer(call -> { ImageInspiration p = call.getArgument(0); p.setId(7L); return p; });
        var task = new Task(); task.setId(70L);
        when(tasks.submit(eq(ImageInspirationGenerateHandler.TYPE), eq("DEFAULT"), eq(Map.of("inspirationId", 7L)),
                eq("image-inspiration-generate-7"), eq(1L))).thenReturn(task);
        var view = TenantContext.runAs(3L, () -> service.create(request(null), 1L));
        assertThat(view.status()).isEqualTo("QUEUED");
        verify(billing).requireMembership("IMAGE_INSPIRATION");
        verify(assets).imageReference(5L);
    }

    @Test void unconfiguredModelIsRejectedBeforeBilling() {
        assertThatThrownBy(() -> service.create(request(null), 1L)).isInstanceOf(BizException.class);
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }

    @Test void needsAnImageOrAnIdea() {
        when(gateway.configured(ModelAlias.IMAGE_INSPIRATION_PLANNER)).thenReturn(true);
        when(inspirations.findByRequestKey("inspiration-key-2")).thenReturn(Optional.empty());
        var empty = new ImageInspirationDtos.Create("inspiration-key-2", List.of(), null, "  ", null);
        assertThatThrownBy(() -> service.create(empty, 1L)).isInstanceOf(BizException.class).hasMessageContaining("参考图");
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }

    @Test void anotherRoundNeedsAFinishedPreviousBatch() {
        when(gateway.configured(ModelAlias.IMAGE_INSPIRATION_PLANNER)).thenReturn(true);
        when(inspirations.findByRequestKey("inspiration-key-1")).thenReturn(Optional.empty());
        var previous = new ImageInspiration(); previous.setStatus("GENERATING");
        when(inspirations.findById(2L)).thenReturn(Optional.of(previous));
        assertThatThrownBy(() -> TenantContext.runAs(3L, () -> service.create(request(2L), 1L)))
                .isInstanceOf(BizException.class).hasMessageContaining("还没有完成");
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }

    @Test void retryingTheSameRequestKeyReturnsTheExistingBatch() {
        when(gateway.configured(ModelAlias.IMAGE_INSPIRATION_PLANNER)).thenReturn(true);
        var prior = new ImageInspiration(); prior.setId(7L); prior.setImageAssetIds(List.of(5L));
        prior.setStyle("写实摄影"); prior.setIdea("新品上新");
        when(inspirations.findByRequestKey("inspiration-key-1")).thenReturn(Optional.of(prior));
        assertThat(service.create(request(null), 1L).id()).isEqualTo(7L);
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(tasks, assets);
    }

    @Test void reusingARequestKeyForDifferentInputIsRejected() {
        when(gateway.configured(ModelAlias.IMAGE_INSPIRATION_PLANNER)).thenReturn(true);
        var prior = new ImageInspiration(); prior.setId(7L); prior.setImageAssetIds(List.of(5L));
        prior.setStyle("国风意境"); prior.setIdea("新品上新");
        when(inspirations.findByRequestKey("inspiration-key-1")).thenReturn(Optional.of(prior));
        assertThatThrownBy(() -> service.create(request(null), 1L)).isInstanceOf(BizException.class).hasMessageContaining("不同的方案");
        verifyNoInteractions(tasks);
    }

    @Test void completionCapturesTheReservationAndStoresThePlans() {
        var inspiration = new ImageInspiration(); inspiration.setId(7L); inspiration.setTaskId(70L); inspiration.setStatus("GENERATING");
        inspiration.setBillingReservationId("r1");
        var task = new Task(); task.setId(70L); task.setAttempts(1);
        when(taskRepository.findOwnedRunning(70L, 1)).thenReturn(Optional.of(task));
        when(inspirations.lock(7L)).thenReturn(Optional.of(inspiration));
        assertThat(service.completed(7L, task, List.of(Map.of("title", "晨光")))).isTrue();
        assertThat(inspiration.getStatus()).isEqualTo("SUCCEEDED");
        verify(billing).capture(eq("r1"), eq((long) BillingService.IMAGE_INSPIRATION_CREDITS), eq("image-inspiration:7:capture"), anyString());
    }

    @Test void terminalTaskFailureReleasesTheReservationOnce() {
        var inspiration = new ImageInspiration(); inspiration.setId(7L); inspiration.setTaskId(70L); inspiration.setStatus("GENERATING");
        inspiration.setBillingReservationId("r1");
        when(inspirations.lock(7L)).thenReturn(Optional.of(inspiration));
        var event = new TaskTerminalFailure(3L, 70L, ImageInspirationGenerateHandler.TYPE, Map.of("inspirationId", 7L));
        service.taskFailed(event);
        service.taskFailed(event);
        assertThat(inspiration.getStatus()).isEqualTo("FAILED");
        verify(billing).release(eq("r1"), eq("image-inspiration:7:release"), anyString());
    }

    @Test void historyListsSucceededBatchesAndKeepsTheSlotOfADeletedImage() {
        var inspiration = new ImageInspiration(); inspiration.setId(7L); inspiration.setStatus("SUCCEEDED");
        inspiration.setImageAssetIds(List.of(5L, 6L)); inspiration.setStyle("写实摄影"); inspiration.setPlans(List.of(Map.of("title", "晨光")));
        when(inspirations.findByStatusOrderByIdDesc(eq("SUCCEEDED"), any())).thenReturn(new PageImpl<>(List.of(inspiration)));
        when(assets.imagePreview(5L)).thenReturn(Optional.of(new AssetService.ImagePreview(5L, "瓶身.png", "https://cdn/5")));
        when(assets.imagePreview(6L)).thenReturn(Optional.empty());
        var item = service.history(0, 10).items().getFirst();
        assertThat(item.style()).isEqualTo("写实摄影");
        assertThat(item.images()).extracting(ImageInspirationDtos.Image::assetId, ImageInspirationDtos.Image::url)
                .containsExactly(tuple(5L, "https://cdn/5"), tuple(6L, null));
    }
}
