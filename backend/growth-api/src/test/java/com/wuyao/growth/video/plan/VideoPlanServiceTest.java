package com.wuyao.growth.video.plan;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.billing.BillingDtos;
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
        when(billing.reserve(eq("video-plan:plan-key-1"), eq("VIDEO_PLAN"), eq((long) BillingService.VIDEO_PLAN_CREDITS), anyString())).thenReturn("r1");
        when(plans.saveAndFlush(any())).thenAnswer(call -> { VideoPlan p = call.getArgument(0); p.setId(7L); return p; });
        var task = new Task(); task.setId(70L);
        when(tasks.submit(eq(VideoPlanGenerateHandler.TYPE), eq("DEFAULT"), eq(Map.of("planId", 7L)), eq("video-plan-generate-7"), eq(1L))).thenReturn(task);
        var view = TenantContext.runAs(3L, () -> service.create(request(null), 1L));
        assertThat(view.status()).isEqualTo("QUEUED");
        verify(billing).requireMembership("VIDEO_PLAN");
        verify(assets).imageReference(5L);
    }

    @Test void theTeamWalletReservesThePlanFromTheTeam() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        when(plans.findByRequestKey("plan-key-1")).thenReturn(Optional.empty());
        when(billing.reserve(eq("video-plan:plan-key-1"), eq("VIDEO_PLAN"), eq((long) BillingService.VIDEO_PLAN_CREDITS), anyString(),
                eq(BillingDtos.GenerationWallet.TEAM))).thenReturn("team-r1");
        when(plans.saveAndFlush(any())).thenAnswer(call -> { VideoPlan p = call.getArgument(0); p.setId(7L); return p; });
        var task = new Task(); task.setId(70L);
        when(tasks.submit(anyString(), anyString(), anyMap(), anyString(), anyLong())).thenReturn(task);
        TenantContext.runAs(3L, () -> service.create(request(null), 1L, BillingDtos.GenerationWallet.TEAM));
        verify(billing, never()).reserve(anyString(), anyString(), anyLong(), anyString());
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

    @Test void durationAndOriginalBriefSurviveTheWorkerQueueAndCannotReuseAnotherRequest() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        when(plans.findByRequestKey("duration-key")).thenReturn(Optional.empty());
        when(plans.saveAndFlush(any())).thenAnswer(call -> { VideoPlan p = call.getArgument(0); p.setId(7L); return p; });
        var task = new Task(); task.setId(70L);
        when(tasks.submit(anyString(), anyString(), any(), anyString(), anyLong())).thenAnswer(call -> { task.setPayload(call.getArgument(2)); return task; });
        var request = new VideoPlanDtos.Create("duration-key", List.of(5L), false, null, "生成20s宣传视频",
                null, null, 10, 30, "9:16", "活动开展10天，人物口播宣传");
        TenantContext.runAs(3L, () -> service.create(request, 1L));
        assertThat((Map<String, Object>) task.getPayload().get("constraints"))
                .containsEntry("durationSeconds", 20).containsEntry("sourceBrief", "活动开展10天，人物口播宣传");
        var prior = new VideoPlan(); prior.setId(7L); prior.setTaskId(70L); prior.setImageAssetIds(List.of(5L)); prior.setIdea(request.idea());
        when(plans.findByRequestKey("duration-key")).thenReturn(Optional.of(prior));
        when(taskRepository.findById(70L)).thenReturn(Optional.of(task));
        TenantContext.runAs(3L, () -> service.create(request, 1L));
        var changed = new VideoPlanDtos.Create("duration-key", List.of(5L), false, null, request.idea(),
                null, null, 10, 30, "16:9", request.sourceBrief());
        assertThatThrownBy(() -> TenantContext.runAs(3L, () -> service.create(changed, 1L))).isInstanceOf(BizException.class);
        verify(billing, times(1)).reserve(anyString(), anyString(), anyLong(), anyString());
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

    @Test void createStoresTheChosenSceneAndDropsFreeOrUnknownKeys() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        when(plans.findByRequestKey(anyString())).thenReturn(Optional.empty());
        var saved = new java.util.ArrayList<VideoPlan>();
        when(plans.saveAndFlush(any())).thenAnswer(call -> { VideoPlan p = call.getArgument(0); p.setId(7L); saved.add(p); return p; });
        when(tasks.submit(anyString(), anyString(), any(), anyString(), anyLong())).thenReturn(new Task());
        for (String key : new String[] {"ecommerce", "free", "no_such_scene"}) {
            var create = new VideoPlanDtos.Create("plan-" + key, List.of(5L), false, null, "x", null, key);
            TenantContext.runAs(3L, () -> service.create(create, 1L));
        }
        assertThat(saved).extracting(VideoPlan::getSceneKey).containsExactly("ecommerce", null, null);
    }

    @Test void anotherRoundKeepsThePreviousBatchSceneWhateverTheWorkbenchShowsNow() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        when(plans.findByRequestKey("plan-key-2")).thenReturn(Optional.empty());
        var previous = new VideoPlan(); previous.setStatus("SUCCEEDED"); previous.setSceneKey("drama");
        when(plans.findById(2L)).thenReturn(Optional.of(previous));
        when(plans.saveAndFlush(any())).thenAnswer(call -> { VideoPlan p = call.getArgument(0); p.setId(8L); return p; });
        when(tasks.submit(anyString(), anyString(), any(), anyString(), anyLong())).thenReturn(new Task());
        var create = new VideoPlanDtos.Create("plan-key-2", List.of(5L), false, null, "x", 2L, "ecommerce");
        var view = TenantContext.runAs(3L, () -> service.create(create, 1L));
        verify(plans).saveAndFlush(argThat(p -> "drama".equals(p.getSceneKey())));
        assertThat(view.sceneKey()).isEqualTo("drama");
    }

    @Test void changingTheSceneCannotReuseAnExistingRequestKey() {
        when(gateway.configured(ModelAlias.VIDEO_PLANNER)).thenReturn(true);
        var prior = new VideoPlan(); prior.setImageAssetIds(List.of(5L));
        prior.setStyle("高级质感"); prior.setIdea("突出补水"); prior.setSceneKey("brand");
        when(plans.findByRequestKey("plan-key-1")).thenReturn(Optional.of(prior));
        var changed = new VideoPlanDtos.Create("plan-key-1", List.of(5L), false, "高级质感", "突出补水", null, "drama");
        assertThatThrownBy(() -> service.create(changed, 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("不同的方案");
        verifyNoInteractions(tasks);
    }

    @Test void expiredWorkersCannotPublishChargeOrRefund() {
        var task = new Task(); task.setId(70L); task.setAttempts(1);
        when(taskRepository.findOwnedRunning(70L, 1)).thenReturn(Optional.empty());
        assertThat(service.begin(7L, task)).isFalse();
        assertThat(service.completed(7L, task, List.of())).isFalse();
        assertThat(service.checkpoint(7L, task, Map.of())).isFalse();
        assertThat(service.needsDetails(7L, task, Map.of("question", "活动权益是什么？"))).isFalse();
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

    @Test void clarificationRefundsOnceAndSurvivesGetWithoutExposingIntermediateDrafts() {
        var task = new Task(); task.setId(70L); task.setAttempts(1);
        var plan = new VideoPlan(); plan.setId(7L); plan.setTaskId(70L); plan.setStatus("GENERATING"); plan.setBillingReservationId("r1");
        when(taskRepository.findOwnedRunning(70L,1)).thenReturn(Optional.of(task));
        when(plans.lock(7L)).thenReturn(Optional.of(plan)); when(plans.findById(7L)).thenReturn(Optional.of(plan));
        assertThat(service.checkpoint(7L,task,Map.of("concept", Map.of("question","")))).isTrue();
        assertThat(service.get(7L).phase()).isEqualTo("DRAFT"); assertThat(service.get(7L).plans()).isEmpty();
        var details = Map.<String,Object>of("question","充值有哪些赠送权益？","originalIdea","双十一20秒推广");
        assertThat(service.needsDetails(7L,task,details)).isTrue();
        assertThat(service.needsDetails(7L,task,details)).isFalse();
        assertThat(service.get(7L).clarification()).isEqualTo(details); assertThat(service.get(7L).status()).isEqualTo("FAILED");
        verify(billing).release(eq("r1"),eq("video-plan:7:release"),anyString());
        verify(billing,never()).capture(anyString(),anyLong(),anyString(),anyString());
    }

    @Test void historyListsSucceededBatchesAndKeepsTheSlotOfADeletedImage() {
        var plan = new VideoPlan(); plan.setId(7L); plan.setStatus("SUCCEEDED"); plan.setImageAssetIds(List.of(5L, 6L));
        plan.setStyle("高级质感"); plan.setIdea("突出补水"); plan.setPlans(List.of(Map.of("title", "晨光"))); plan.setSceneKey("ecommerce");
        when(plans.findByStatusOrderByIdDesc(eq("SUCCEEDED"), any())).thenReturn(new PageImpl<>(List.of(plan)));
        when(assets.imagePreview(5L)).thenReturn(Optional.of(new AssetService.ImagePreview(5L, "瓶身.png", "https://cdn/5")));
        when(assets.imagePreview(6L)).thenReturn(Optional.empty());
        var item = service.history(0, 10).items().getFirst();
        assertThat(item.style()).isEqualTo("高级质感");
        assertThat(item.sceneKey()).isEqualTo("ecommerce");
        assertThat(item.plans()).hasSize(1);
        assertThat(item.images()).extracting(VideoPlanDtos.Image::assetId, VideoPlanDtos.Image::url)
                .containsExactly(tuple(5L, "https://cdn/5"), tuple(6L, null));
    }
}
