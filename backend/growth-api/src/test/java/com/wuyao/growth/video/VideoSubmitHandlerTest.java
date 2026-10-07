package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class VideoSubmitHandlerTest {
    @Test
    void anUnsubmittedRetryRefreshesAPersistedExpiredReference() {
        var service = mock(VideoWorkflowService.class);
        var provider = mock(VideoProviderGateway.class);
        var workflows = mock(VideoWorkflowRepository.class);
        var workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setRequest(new VideoDtos.Create("request-42", "旋转", java.util.List.of(9L), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p"));
        Map<String, Object> expired = Map.of("content", java.util.List.of(Map.of("type", "image_url",
                "image_url", Map.of("url", "https://storage.example/image?X-Amz-Date=20200101T000000Z&X-Amz-Expires=3600"))));
        Map<String, Object> fresh = Map.of("content", java.util.List.of(Map.of("type", "image_url",
                "image_url", Map.of("url", "https://storage.example/fresh"))));
        workflow.setProviderSubmitRequest(expired);
        Task task = task();
        when(service.beginSubmit(42L, task)).thenReturn(true);
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
        when(provider.buildSubmitRequest(workflow.getRequest())).thenReturn(fresh);
        when(service.prepareSubmissionRequest(eq(42L), same(task), anyMap()))
                .thenAnswer(invocation -> Optional.of(new ObjectMapper().writeValueAsString(invocation.getArgument(2))));
        when(provider.submit(anyString(), eq("video-42-submit")))
                .thenReturn(new VideoProviderGateway.SubmitResult("onlyrouter", "job-42", "QUEUED"));

        var result = new VideoSubmitHandler(service, provider, workflows).handle(task);

        assertThat(result).containsEntry("status", "SUBMITTED");
        verify(provider).buildSubmitRequest(workflow.getRequest());
        verify(provider).submit(eq(serialize(fresh)), eq("video-42-submit"));
    }

    @Test
    void retriesReuseThePersistedProviderRequestWithoutRegeneratingSignedUrls() {
        var service = mock(VideoWorkflowService.class);
        var provider = mock(VideoProviderGateway.class);
        var workflows = mock(VideoWorkflowRepository.class);
        var workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setRequest(new VideoDtos.Create("request-42", "旋转", java.util.List.of(), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p"));
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("model", "doubao-seedance-2-0-mini-260128");
        persisted.put("content", java.util.List.of(Map.of("type", "image_url",
                "role", "reference_image", "image_url", Map.of("url", "https://signed/first"))));
        workflow.setProviderSubmitRequest(persisted);
        workflow.setProviderSubmitStartedAt(Instant.now());
        String body = serialize(persisted);
        workflow.setProviderSubmitBody(body);
        Task task = task();
        when(service.beginSubmit(eq(42L), same(task))).thenReturn(true);
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
        when(service.prepareSubmissionRequest(eq(42L), same(task), same(persisted)))
                .thenReturn(Optional.of(body));
        when(provider.submit(eq(body), eq("video-42-submit")))
                .thenReturn(new VideoProviderGateway.SubmitResult("onlyrouter", "job-42", "QUEUED"));

        var handler = new VideoSubmitHandler(service, provider, workflows);
        Map<String, Object> result = handler.handle(task);

        assertThat(result).containsEntry("status", "SUBMITTED");
        verify(provider, never()).buildSubmitRequest(any());
        verify(provider).submit(eq(body), eq("video-42-submit"));
        verify(service).saveSubmission(eq(42L), same(task), any(VideoProviderGateway.SubmitResult.class));
    }

    private Task task() {
        Task task = new Task();
        task.setId(7L);
        task.setAttempts(1);
        task.setPayload(Map.of("workflowId", 42L));
        return task;
    }

    private String serialize(Map<String, Object> body) {
        try { return new ObjectMapper().writeValueAsString(body); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
