package com.wuyao.growth.video;

import com.wuyao.growth.common.task.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoProviderHandlerRetryTest {
    @ParameterizedTest
    @CsvSource({"SUBMIT, 400, 1, false", "POLL, 404, 1, false", "POLL, 410, 1, false",
            "SUBMIT, 408, 1, true", "POLL, 408, 1, true", "SUBMIT, 429, 1, true", "POLL, 429, 1, true",
            "SUBMIT, 503, 1, true", "POLL, 503, 1, true", "SUBMIT, 503, 3, true", "POLL, 503, 3, true"})
    void permanentHttpErrorsEndImmediatelyAndTransientErrorsOnlyEndOnExhaustion(String stage, int status, int attempts, boolean retryable) {
        var service = mock(VideoWorkflowService.class);
        var provider = mock(VideoProviderGateway.class);
        var workflows = mock(VideoWorkflowRepository.class);
        var workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setProviderJobId("job-42");
        workflow.setProviderSubmitStartedAt(Instant.now());
        workflow.setProviderSubmitRequest(Map.of());
        var task = new Task();
        task.setPayload(Map.of("workflowId", 42L));
        task.setAttempts(attempts);
        task.setMaxAttempts(3);
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
        when(service.beginSubmit(42L, task)).thenReturn(true);
        when(service.beginPoll(42L, task)).thenReturn(true);
        when(service.prepareSubmissionRequest(eq(42L), same(task), anyMap())).thenReturn(Optional.of("{}"));
        var original = new VideoProviderGateway.ProviderHttpException(status, "HTTP " + status);
        when(provider.submit(anyString(), anyString())).thenThrow(original);
        when(provider.poll(anyString(), anyString())).thenThrow(original);
        TaskHandler handler = stage.equals("SUBMIT") ? new VideoSubmitHandler(service, provider, workflows)
                : new VideoPollHandler(service, provider, workflows);
        if (!retryable) {
            assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(NonRetryableTaskException.class).hasCause(original);
            verify(service).markFailed(42L, task, stage.equals("SUBMIT") ? "VIDEO_PROVIDER_REJECTED" : "VIDEO_PROVIDER_POLL_REJECTED", original.getMessage());
        } else {
            assertThatThrownBy(() -> handler.handle(task)).isSameAs(original);
            if (attempts == 3) verify(service).markFailed(42L, task, "VIDEO_" + stage + "_FAILED", original.getMessage());
            else verify(service, never()).markFailed(eq(42L), any(Task.class), anyString(), anyString());
        }
    }
}
