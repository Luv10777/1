package com.wuyao.growth.video;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.metrics.VideoMetrics;
import com.wuyao.growth.asset.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoImportHandlerTest {
    @ParameterizedTest
    @ValueSource(strings = {"INVALID_CONTENT", "MISSING_URL", "NETWORK"})
    void theRealDownloaderFailsPermanentErrorsImmediatelyButKeepsNetworkFailuresActive(String failure) throws Exception {
        var workflows = mock(VideoWorkflowRepository.class);
        var storage = mock(ObjectStorage.class);
        var permits = mock(TenantRateLimiter.class);
        var tasks = mock(TaskService.class);
        var http = mock(HttpClient.class);
        var task = task(1);
        task.setId(8L);
        var workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setTenantId(7L);
        workflow.setTaskId(8L);
        workflow.setStatus("IMPORTING");
        workflow.setStage("IMPORT");
        workflow.setVideoConcurrencyPermitHeld(true);
        workflow.setProviderResultUrl(failure.equals("MISSING_URL") ? null : "https://example.com/output.mp4");
        when(workflows.lock(42L)).thenReturn(Optional.of(workflow));
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
        when(tasks.ownsExecution(task)).thenReturn(true);
        if (failure.equals("NETWORK")) {
            when(http.execute(any(HttpGet.class), any(HttpClientResponseHandler.class)))
                    .thenThrow(new java.io.IOException("connection reset"));
        } else {
            var response = mock(ClassicHttpResponse.class);
            when(response.getCode()).thenReturn(200);
            when(response.getEntity()).thenReturn(new ByteArrayEntity(new byte[32], ContentType.TEXT_HTML));
            when(http.execute(any(HttpGet.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation ->
                    ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response));
            doAnswer(invocation -> {
                ((java.io.InputStream) invocation.getArgument(1)).transferTo(java.io.OutputStream.nullOutputStream());
                return null;
            }).when(storage).put(anyString(), any(java.io.InputStream.class), anyLong(), anyString());
        }
        var service = new VideoWorkflowService(workflows, mock(VideoProviderJobRepository.class), mock(AssetRepository.class),
                mock(AssetService.class), storage, tasks, new ObjectMapper(), permits, http, mock(VideoMediaProbe.class), mock(VideoMetrics.class));
        ReflectionTestUtils.setField(service, "maxDurationSeconds", 7200L);
        ReflectionTestUtils.setField(service, "maxProviderBytes", 1024L);
        var handler = new VideoImportHandler(service, workflows);
        if (failure.equals("NETWORK")) {
            assertThatThrownBy(() -> handler.handle(task)).isNotInstanceOf(NonRetryableTaskException.class)
                    .hasRootCauseMessage("connection reset");
            assertThat(workflow.getStatus()).isEqualTo("IMPORTING");
            assertThat(workflow.isVideoConcurrencyPermitHeld()).isTrue();
            verify(permits, never()).releaseVideoGeneration(anyLong());
        } else {
            assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(NonRetryableTaskException.class);
            assertThat(workflow.getStatus()).isEqualTo("FAILED");
            assertThat(workflow.getErrorCode()).isEqualTo("VIDEO_IMPORT_REJECTED");
            assertThat(workflow.isVideoConcurrencyPermitHeld()).isFalse();
            verify(permits).releaseVideoGeneration(7L);
        }
    }

    @Test
    void reservesTheObjectKeyBeforeNetworkIoAndUsesItForBothDownloadAndCommit() {
        var service = mock(VideoWorkflowService.class);
        var workflows = mock(VideoWorkflowRepository.class);
        var task = task(1);
        var workflow = new VideoWorkflow();
        String key = "t7/generated-video/42/import-key/output.mp4";
        when(service.beginImport(42L, task)).thenReturn(true);
        when(service.reserveImportKey(42L, task)).thenReturn(Optional.of(key));
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
        when(service.importUrl(workflow, key)).thenReturn(new VideoWorkflowService.ImportedVideo(key, 100, "video/mp4"));
        assertThat(new VideoImportHandler(service, workflows).handle(task)).containsEntry("status", "IMPORTED");
        var order = inOrder(service, workflows);
        order.verify(service).beginImport(42L, task);
        order.verify(service).reserveImportKey(42L, task);
        order.verify(workflows).findById(42L);
        order.verify(service).importUrl(workflow, key);
        order.verify(service).saveImportedVideo(42L, task, key, 100, "video/mp4");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void failedAttemptsCleanTheirRegisteredKeyAndOnlyExhaustionFailsTheWorkflow(int attempts) {
        var service = mock(VideoWorkflowService.class);
        var workflows = mock(VideoWorkflowRepository.class);
        var task = task(attempts);
        var workflow = new VideoWorkflow();
        String key = "t7/generated-video/42/import-key/output.mp4";
        var original = new IllegalStateException("download failed");
        when(service.beginImport(42L, task)).thenReturn(true);
        when(service.reserveImportKey(42L, task)).thenReturn(Optional.of(key));
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
        when(service.importUrl(workflow, key)).thenThrow(original);
        assertThatThrownBy(() -> new VideoImportHandler(service, workflows).handle(task)).isSameAs(original);
        verify(service).cleanupImportObject(42L, key);
        if (attempts == 3) verify(service).markFailed(42L, task, "VIDEO_IMPORT_FAILED", "download failed");
        else verify(service, never()).markFailed(eq(42L), any(Task.class), anyString(), anyString());
    }

    @Test
    void anExecutionThatCannotReserveAKeyNeverStartsTheDownload() {
        var service = mock(VideoWorkflowService.class);
        var workflows = mock(VideoWorkflowRepository.class);
        var task = task(1);
        when(service.beginImport(42L, task)).thenReturn(true);
        when(service.reserveImportKey(42L, task)).thenReturn(Optional.empty());
        assertThat(new VideoImportHandler(service, workflows).handle(task)).containsEntry("status", "STALE");
        verifyNoInteractions(workflows);
        verify(service, never()).importUrl(any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"BEGIN, 1", "BEGIN, 3", "RESERVE, 1", "RESERVE, 3"})
    void failuresBeforeAKeyIsReservedAlsoHaveAnExhaustionFallback(String step, int attempts) {
        var service = mock(VideoWorkflowService.class);
        var workflows = mock(VideoWorkflowRepository.class);
        var task = task(attempts);
        var original = new IllegalStateException("database unavailable");
        if (step.equals("BEGIN")) when(service.beginImport(42L, task)).thenThrow(original);
        else {
            when(service.beginImport(42L, task)).thenReturn(true);
            when(service.reserveImportKey(42L, task)).thenThrow(original);
        }
        assertThatThrownBy(() -> new VideoImportHandler(service, workflows).handle(task)).isSameAs(original);
        verify(service, never()).cleanupImportObject(anyLong(), anyString());
        if (attempts == 3) verify(service).markFailed(42L, task, "VIDEO_IMPORT_FAILED", original.getMessage());
        else verify(service, never()).markFailed(eq(42L), any(Task.class), anyString(), anyString());
    }

    private Task task(int attempts) {
        var task = new Task();
        task.setPayload(Map.of("workflowId", 42L));
        task.setAttempts(attempts);
        task.setMaxAttempts(3);
        return task;
    }
}
