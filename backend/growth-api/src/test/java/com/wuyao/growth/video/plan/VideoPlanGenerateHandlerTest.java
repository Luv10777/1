package com.wuyao.growth.video.plan;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoPlanGenerateHandlerTest {
    private final VideoPlanService service = mock(VideoPlanService.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final VideoPlanGenerateHandler handler = new VideoPlanGenerateHandler(service, mock(AssetService.class), mock(ObjectStorage.class), gateway, new VideoPlanProperties());
    private final Task task = new Task();
    private final VideoPlan plan = new VideoPlan();
    VideoPlanGenerateHandlerTest() {
        task.setTenantId(2L); task.setPayload(Map.of("planId",21L,"constraints",Map.of("durationSeconds",20,"maxDurationSeconds",30,"ratio","9:16","sourceBrief","活动10天，用参考人物口播宣传")));
        plan.setIdea("为充值活动生成20s宣传视频");
        when(service.begin(21L,task)).thenReturn(true); when(service.processing(21L)).thenReturn(plan); when(service.previousPlans(null)).thenReturn(List.of());
        when(service.checkpoint(eq(21L),eq(task),anyMap())).thenAnswer(call -> {
            Map<String,Object> copy = new LinkedHashMap<>(call.getArgument(2)); plan.setPlans(List.of(Map.of("pipelineVersion",2,"stages",copy))); return true;
        });
        when(service.completed(eq(21L),eq(task),anyList())).thenReturn(true);
    }
    private static ProviderResult result(Map<String,Object> data) { return new ProviderResult(true,"TEST",null,data,null,null); }
    @Test void twentySecondConstraintsReachEveryStageAndWrongDurationCannotComplete() {
        var wrong = VideoPlanScriptTest.reviewed(); VideoPlanScriptTest.plans(wrong).getFirst().put("durationSeconds",15);
        when(gateway.invokeReal(any())).thenAnswer(call -> {
            ProviderRequest request = call.getArgument(0);
            assertThat(request.prompt()).contains("三个方案都必须为 20 秒","活动10天，用参考人物口播宣传","画幅固定 9:16");
            return result(request.idempotencyKey().endsWith("concept") ? VideoPlanScriptTest.concept(false) : wrong);
        });
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(NonRetryableTaskException.class);
        verify(service,never()).completed(anyLong(),any(),anyList()); verify(service).failed(eq(21L),eq(task),contains("时长"));
    }
    @Test void threeStagesRunInOrderAndOnlyReviewedScriptsArePublished() {
        var calls = new ArrayList<String>();
        when(gateway.invokeReal(any())).thenAnswer(call -> { ProviderRequest r=call.getArgument(0); calls.add(r.idempotencyKey());
            return result(r.idempotencyKey().endsWith("concept")?VideoPlanScriptTest.concept(false):VideoPlanScriptTest.reviewed()); });
        assertThat(handler.handle(task)).containsEntry("status","SUCCEEDED");
        assertThat(calls).containsExactly("video-plan-21-v2-concept","video-plan-21-v2-draft","video-plan-21-v2-review");
        verify(service).completed(eq(21L),eq(task),argThat(list -> list.size()==3 && list.getFirst().get("promptVersion").equals(2)));
    }
    @Test void aQuestionRefundsWithoutWritingDraftsOrPublishingPlans() {
        when(gateway.invokeReal(any())).thenReturn(result(VideoPlanScriptTest.concept(true))); when(service.needsDetails(eq(21L),eq(task),anyMap())).thenReturn(true);
        assertThat(handler.handle(task)).containsEntry("status","NEEDS_DETAILS");
        verify(gateway).invokeReal(any()); verify(service).needsDetails(eq(21L),eq(task),argThat(d -> d.get("question").toString().contains("充值档位") && d.get("originalIdea").equals(plan.getIdea())));
        verify(service,never()).completed(anyLong(),any(),anyList()); verify(service,never()).checkpoint(anyLong(),any(),anyMap());
    }
    @Test void retryAfterReviewNetworkFailureReusesPersistedConceptAndDraft() {
        var calls = new ArrayList<String>(); var failReview = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(gateway.invokeReal(any())).thenAnswer(call -> { ProviderRequest r=call.getArgument(0); calls.add(r.idempotencyKey());
            if(r.idempotencyKey().endsWith("review") && failReview.getAndSet(false)) throw new IllegalStateException("network");
            return result(r.idempotencyKey().endsWith("concept")?VideoPlanScriptTest.concept(false):VideoPlanScriptTest.reviewed()); });
        task.setMaxAttempts(3); task.setAttempts(1);
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(IllegalStateException.class);
        assertThat(handler.handle(task)).containsEntry("status","SUCCEEDED");
        assertThat(calls).containsExactly("video-plan-21-v2-concept","video-plan-21-v2-draft","video-plan-21-v2-review","video-plan-21-v2-review");
        verify(service,never()).failed(anyLong(),any(),anyString());
    }
    @Test void lostLeaseStopsBeforeAnotherModelCall() {
        when(service.checkpoint(eq(21L),eq(task),anyMap())).thenReturn(false); when(gateway.invokeReal(any())).thenReturn(result(VideoPlanScriptTest.concept(false)));
        assertThat(handler.handle(task)).containsEntry("status","STALE"); verify(gateway).invokeReal(any()); verify(service,never()).completed(anyLong(),any(),anyList());
    }
}
