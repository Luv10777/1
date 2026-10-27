package com.wuyao.growth.video.plan;

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

class VideoPlanServiceTest {
    private final VideoPlanRepository plans = mock(VideoPlanRepository.class);
    private final AssetService assets = mock(AssetService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final BillingService billing = mock(BillingService.class);
    private final VideoPlanService service = new VideoPlanService(plans, assets, tasks, taskRepository, gateway, billing);

    private static VideoPlanDtos.Create request(Long previous) {
        return new VideoPlanDtos.Create("plan-key-1", List.of(5L), false, "高级质感", "突出补水", previous);
    }

    @Test void createReservesOneCreditAndQueuesGeneration() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        when(plans.findByRequestKey("plan-key-1")).thenReturn(Optional.empty());
        when(billing.reserve(eq("video-plan:plan-key-1"), eq("VIDEO_PLAN"), eq(1L), anyString())).thenReturn("r1");
        when(plans.saveAndFlush(any())).thenAnswer(call -> { VideoPlan p = call.getArgument(0); p.setId(7L); return p; });
        var task = new Task(); task.setId(70L);
        when(tasks.submit(eq(VideoPlanGenerateHandler.TYPE), eq("DEFAULT"), eq(Map.of("planId", 7L)), eq("video-plan-generate-7"), eq(1L))).thenReturn(task);
        var view = TenantContext.runAs(3L, () -> service.create(request(null), 1L));
        assertThat(view.status()).isEqualTo("QUEUED");
        verify(billing).requireMembership("VIDEO_PLAN");
        verify(assets).imageReference(5L);
    }

    @Test void anotherRoundNeedsAFinishedPreviousBatch() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        when(plans.findByRequestKey("plan-key-1")).thenReturn(Optional.empty());
        var previous = new VideoPlan(); previous.setStatus("GENERATING");
        when(plans.findById(2L)).thenReturn(Optional.of(previous));
        assertThatThrownBy(() -> TenantContext.runAs(3L, () -> service.create(request(2L), 1L)))
                .isInstanceOf(BizException.class).hasMessageContaining("还没有完成");
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }

    @Test void unconfiguredModelIsRejectedBeforeBilling() {
        assertThatThrownBy(() -> service.create(request(null), 1L)).isInstanceOf(BizException.class);
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
    }

    @Test void retryingTheSameRequestReturnsTheExistingPlanWithoutAnotherReservation() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        var prior = new VideoPlan(); prior.setId(7L); prior.setImageAssetIds(List.of(5L));
        prior.setStyle("高级质感"); prior.setIdea("突出补水");
        when(plans.findByRequestKey("plan-key-1")).thenReturn(Optional.of(prior));
        assertThat(service.create(request(null), 1L).id()).isEqualTo(7L);
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(tasks, assets);
    }

    @Test void changingTheVideoReferenceCannotReuseAnExistingRequestKey() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        var prior = new VideoPlan(); prior.setImageAssetIds(List.of(5L));
        prior.setStyle("高级质感"); prior.setIdea("突出补水");
        when(plans.findByRequestKey("plan-key-1")).thenReturn(Optional.of(prior));
        var changed = new VideoPlanDtos.Create("plan-key-1", List.of(5L), true, "高级质感", "突出补水", null);
        assertThatThrownBy(() -> service.create(changed, 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("不同的方案");
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(tasks);
    }

    @Test void expiredWorkersCannotPublishChargeOrRefund() {
        var task = new Task(); task.setId(70L); task.setAttempts(1);
        when(taskRepository.findOwnedRunning(70L, 1)).thenReturn(Optional.empty());
        assertThat(service.begin(7L, task)).isFalse();
        assertThat(service.completed(7L, task, List.of())).isFalse();
        service.failed(7L, task, "stale failure");
        verifyNoInteractions(plans);
        verify(billing, never()).capture(anyString(), anyLong(), anyString(), anyString());
        verify(billing, never()).release(anyString(), anyString(), anyString());
    }

    @Test void terminalTaskFailureReleasesTheReservation() {
        var plan = new VideoPlan(); plan.setId(7L); plan.setTaskId(70L); plan.setStatus("GENERATING"); plan.setBillingReservationId("r1");
        when(plans.lock(7L)).thenReturn(Optional.of(plan));
        service.taskFailed(new TaskTerminalFailure(3L, 70L, VideoPlanGenerateHandler.TYPE, Map.of("planId", 7L)));
        service.taskFailed(new TaskTerminalFailure(3L, 70L, VideoPlanGenerateHandler.TYPE, Map.of("planId", 7L)));
        assertThat(plan.getStatus()).isEqualTo("FAILED");
        verify(billing).release(eq("r1"), eq("video-plan:7:release"), anyString());
    }

    @Test void historyListsSucceededBatchesAndKeepsTheSlotOfADeletedImage() {
        var plan = new VideoPlan(); plan.setId(7L); plan.setStatus("SUCCEEDED"); plan.setImageAssetIds(List.of(5L, 6L));
        plan.setStyle("高级质感"); plan.setIdea("突出补水"); plan.setPlans(List.of(Map.of("title", "晨光")));
        when(plans.findByStatusOrderByIdDesc(eq("SUCCEEDED"), any())).thenReturn(new PageImpl<>(List.of(plan)));
        when(assets.imagePreview(5L)).thenReturn(Optional.of(new AssetService.ImagePreview(5L, "瓶身.png", "https://cdn/5")));
        when(assets.imagePreview(6L)).thenReturn(Optional.empty());
        var item = service.history(0, 10).items().getFirst();
        assertThat(item.style()).isEqualTo("高级质感");
        assertThat(item.plans()).hasSize(1);
        assertThat(item.images()).extracting(VideoPlanDtos.Image::assetId, VideoPlanDtos.Image::url)
                .containsExactly(tuple(5L, "https://cdn/5"), tuple(6L, null));
    }
}
