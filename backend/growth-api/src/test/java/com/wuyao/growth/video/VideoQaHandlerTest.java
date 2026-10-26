package com.wuyao.growth.video;

import com.wuyao.growth.billing.VideoPricingService;
import com.wuyao.growth.common.task.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoQaHandlerTest {
    private final VideoWorkflowService service = mock(VideoWorkflowService.class);
    private final VideoProviderGateway provider = mock(VideoProviderGateway.class);
    private final VideoWorkflowRepository workflows = mock(VideoWorkflowRepository.class);
    private final VideoQaHandler handler = new VideoQaHandler(service, provider, workflows);

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void unexpectedQaFailuresRetryButExhaustionAlsoEndsTheWorkflow(int attempts) {
        var task = task();
        task.setAttempts(attempts);
        task.setMaxAttempts(3);
        var original = com.wuyao.growth.common.web.BizException.of(
                com.wuyao.growth.common.web.ErrorCode.STORAGE_UNAVAILABLE, "MinIO unavailable");
        doThrow(original).when(service).completeQa(42L, task, null);
        assertThatThrownBy(() -> handler.handle(task)).isSameAs(original);
        if (attempts == 3) verify(service).markFailed(42L, task, "VIDEO_QA_FAILED", "MinIO unavailable");
        else verify(service, never()).markFailed(eq(42L), any(Task.class), anyString(), anyString());
    }

    @Test
    void billedWorkflowsSettleWithTheQuotaOfTheRelayThatAcceptedTheQuote() {
        var task = task();
        when(workflows.findById(42L)).thenReturn(Optional.of(billed("https://haomaotoken.com")));
        when(provider.baseUrl()).thenReturn("https://haomaotoken.com");
        when(provider.billedQuota("task_abc")).thenReturn(2185000L);
        handler.handle(task);
        verify(service).completeQa(42L, task, 2185000L);
    }

    @Test
    void unbilledOrSwitchedProviderWorkflowsNeverAskTheRelay() {
        var task = task();
        when(provider.baseUrl()).thenReturn("https://another-relay.example");
        when(workflows.findById(42L)).thenReturn(Optional.of(billed("https://haomaotoken.com")));
        handler.handle(task);
        var unbilled = billed("https://another-relay.example");
        unbilled.setBillingReservationId(null);
        when(workflows.findById(42L)).thenReturn(Optional.of(unbilled));
        handler.handle(task);
        verify(provider, never()).billedQuota(anyString());
        verify(service, times(2)).completeQa(42L, task, null);
    }

    private Task task() {
        var task = new Task();
        task.setPayload(Map.of("workflowId", 42L));
        return task;
    }

    private VideoWorkflow billed(String providerBaseUrl) {
        var workflow = new VideoWorkflow();
        workflow.setProviderJobId("task_abc");
        workflow.setBillingReservationId("reservation-1");
        workflow.setBillingQuote(new VideoPricingService.Quote(true, 20, "", providerBaseUrl, "seedance-2.0", "720p", "t2v",
                0, 0, null, null, null, 0, 0, 0, null, null, null, null));
        return workflow;
    }
}
