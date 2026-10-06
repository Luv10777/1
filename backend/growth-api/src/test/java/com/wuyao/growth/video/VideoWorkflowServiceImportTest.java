package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetRepository;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.asset.VideoMediaProbe;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
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

import java.util.concurrent.atomic.AtomicLong;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoWorkflowServiceImportTest {
    @ParameterizedTest
    @CsvSource({"REDIRECT_LIMIT, REDIRECT_LIMIT, false", "MISSING_LOCATION, REDIRECT_LOCATION_MISSING, false",
            "UNSAFE_REDIRECT, INVALID_URL, false", "MALFORMED_REDIRECT, INVALID_URL, false", "BAD_INITIAL_URL, INVALID_URL, false",
            "MISSING_URL, MISSING_URL, false", "HTTP_ERROR, HTTP_TRANSIENT, true", "MISSING_ENTITY, EMPTY_RESPONSE, false",
            "DECLARED_SIZE, TOO_LARGE, false", "STREAM_SIZE, TOO_LARGE, false", "WRAPPED_STREAM_SIZE, TOO_LARGE, false",
            "SINGLE_READ_SIZE, TOO_LARGE, false", "EMPTY, EMPTY_RESPONSE, false", "INVALID_CONTENT, INVALID_CONTENT, false",
            "INVALID_HEADER, INVALID_CONTENT, false", "STREAM_READ, TRANSFER_FAILED, true", "TRUNCATED_STREAM, TRANSFER_FAILED, true",
            "STORAGE_WRITE, TRANSFER_FAILED, true"})
    void everyImportFailureIsClassifiedAndDeletesOnlyItsIndependentCandidate(String failure, String reason, boolean retryable) throws Exception {
        var storage = mock(ObjectStorage.class);
        var httpClient = mock(HttpClient.class);
        var response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(failure.contains("REDIRECT") || failure.equals("MISSING_LOCATION") ? 302
                : failure.equals("HTTP_ERROR") ? 503 : 200);
        byte[] bytes = failure.equals("EMPTY") ? new byte[0] : new byte[32];
        if (failure.equals("STREAM_READ")) {
            var entity = mock(org.apache.hc.core5.http.HttpEntity.class);
            when(entity.getContentLength()).thenReturn(-1L);
            when(entity.getContentType()).thenReturn("video/mp4");
            when(entity.getContent()).thenReturn(new java.io.InputStream() {
                int reads;
                @Override public int read() throws java.io.IOException {
                    if (++reads > 4) throw new java.io.IOException("stream interrupted");
                    return 1;
                }
            });
            when(response.getEntity()).thenReturn(entity);
        } else if (!failure.equals("MISSING_ENTITY")) {
            when(response.getEntity()).thenReturn(new ByteArrayEntity(bytes, ContentType.create(failure.equals("INVALID_CONTENT")
                    ? "text/html" : failure.equals("INVALID_HEADER") ? "application/octet-stream" : "video/mp4")));
        }
        if (failure.endsWith("SIZE") && !failure.equals("DECLARED_SIZE") || failure.equals("TRUNCATED_STREAM")) {
            var entity = mock(org.apache.hc.core5.http.HttpEntity.class);
            when(entity.getContentLength()).thenReturn(failure.equals("TRUNCATED_STREAM") ? 100L : -1L);
            when(entity.getContentType()).thenReturn("video/mp4");
            when(entity.getContent()).thenReturn(new java.io.ByteArrayInputStream(bytes));
            when(response.getEntity()).thenReturn(entity);
        }
        if (failure.equals("REDIRECT_LIMIT") || failure.equals("UNSAFE_REDIRECT") || failure.equals("MALFORMED_REDIRECT")) {
            when(response.getFirstHeader("Location")).thenReturn(new org.apache.hc.core5.http.message.BasicHeader("Location",
                    failure.equals("UNSAFE_REDIRECT") ? "http://127.0.0.1/private" : failure.equals("MALFORMED_REDIRECT")
                            ? "https://[broken" : "https://example.com/again"));
        }
        when(httpClient.execute(any(HttpGet.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation ->
                ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response));
        doAnswer(invocation -> {
            var input = (java.io.InputStream) invocation.getArgument(1);
            if (failure.equals("STORAGE_WRITE")) { input.read(); throw new IllegalStateException("write failed midway"); }
            if (failure.equals("WRAPPED_STREAM_SIZE")) {
                try { input.transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (Exception e) { throw com.wuyao.growth.common.web.BizException.of(
                        com.wuyao.growth.common.web.ErrorCode.STORAGE_UNAVAILABLE, "wrapped by MinIO without its cause"); }
            } else if (failure.equals("SINGLE_READ_SIZE")) {
                while (input.read() != -1) { /* exercise the scalar read limit */ }
            } else input.transferTo(java.io.OutputStream.nullOutputStream());
            return null;
        }).when(storage).put(anyString(), any(java.io.InputStream.class), anyLong(), anyString());
        var service = service(storage, httpClient);
        ReflectionTestUtils.setField(service, "maxProviderBytes", failure.endsWith("SIZE") ? 8L : 1024L);
        var workflow = new VideoWorkflow();
        workflow.setId(7L);
        workflow.setTenantId(3L);
        workflow.setProviderResultUrl(failure.equals("MISSING_URL") ? null : failure.equals("BAD_INITIAL_URL")
                ? "http://127.0.0.1/private" : "https://example.com/video.mp4");

        assertThatThrownBy(() -> service.importUrl(workflow)).isInstanceOfSatisfying(VideoImportException.class, e -> {
            assertThat(e.reason().name()).isEqualTo(reason);
            assertThat(e.retryable()).isEqualTo(retryable);
        });

        verify(storage).delete(matches("t3/generated-video/7/import-[0-9a-f-]{36}/output\\.mp4"));
        verify(storage, never()).delete("t3/generated-video/7/output.mp4");
        if (failure.equals("REDIRECT_LIMIT")) verify(httpClient, times(6)).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
    }

    @ParameterizedTest
    @CsvSource({"101, false, HTTP_REJECTED", "400, false, HTTP_REJECTED", "401, false, HTTP_REJECTED",
            "403, false, HTTP_REJECTED", "404, false, RESULT_URL_UNAVAILABLE", "410, false, RESULT_URL_UNAVAILABLE",
            "408, true, HTTP_TRANSIENT", "429, true, HTTP_TRANSIENT", "500, true, HTTP_TRANSIENT", "503, true, HTTP_TRANSIENT"})
    void downloadHttpStatusKeepsItsRetryDecision(int status, boolean retryable, String reason) throws Exception {
        var storage = mock(ObjectStorage.class);
        var http = mock(HttpClient.class);
        var response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(status);
        when(http.execute(any(HttpGet.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation ->
                ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response));
        var workflow = workflow();
        assertThatThrownBy(() -> service(storage, http).importUrl(workflow)).isInstanceOfSatisfying(VideoImportException.class, e -> {
            assertThat(e.reason().name()).isEqualTo(reason);
            assertThat(e.retryable()).isEqualTo(retryable);
            if (status == 404 || status == 410) assertThat(e.getMessage()).contains("可能已失效", "原供应商任务", "不要重复提交");
        });
        verify(storage, never()).put(anyString(), any(java.io.InputStream.class), anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dnsFailureRemainsRetryableForTheInitialUrlAndForRedirects(boolean redirect) throws Exception {
        var storage = mock(ObjectStorage.class);
        var http = mock(HttpClient.class);
        var response = mock(ClassicHttpResponse.class);
        String unresolved = "https://unresolved.example/output.mp4";
        when(response.getCode()).thenReturn(302);
        when(response.getFirstHeader("Location")).thenReturn(new org.apache.hc.core5.http.message.BasicHeader("Location", unresolved));
        when(http.execute(any(HttpGet.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation ->
                ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response));
        var workflow = workflow();
        if (!redirect) workflow.setProviderResultUrl(unresolved);
        try (var urls = mockStatic(VideoUrlSecurity.class)) {
            urls.when(() -> VideoUrlSecurity.checkedHttps("https://example.com/video.mp4"))
                    .thenReturn(java.net.URI.create("https://example.com/video.mp4"));
            urls.when(() -> VideoUrlSecurity.checkedHttps(unresolved)).thenThrow(new IllegalArgumentException(
                    "unresolved", new java.net.UnknownHostException("dns offline")));
            assertThatThrownBy(() -> service(storage, http).importUrl(workflow)).isInstanceOfSatisfying(VideoImportException.class,
                    e -> assertThat(e.retryable()).isTrue()).hasRootCauseMessage("dns offline");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "FOREIGN_KEY", "EXISTING_ASSET"})
    void protectedObjectsAreRejectedBeforeHttpOrDeletion(String protection) {
        var storage = mock(ObjectStorage.class);
        var http = mock(HttpClient.class);
        var service = service(storage, http);
        var workflow = workflow();
        String key = protection.equals("FOREIGN_KEY") ? "t999/generated-video/7/output.mp4" : "t3/generated-video/7/output.mp4";
        if (protection.equals("PUBLISHED")) workflow.setStatus("SUCCEEDED");
        if (protection.equals("EXISTING_ASSET")) {
            var repository = (AssetRepository) ReflectionTestUtils.getField(service, "assets");
            when(repository.findByStorageKey(key)).thenReturn(Optional.of(new com.wuyao.growth.asset.Asset()));
        }
        assertThatThrownBy(() -> service.importUrl(workflow, key)).isInstanceOfSatisfying(VideoImportException.class, e -> {
            assertThat(e.reason()).isEqualTo(VideoImportException.Reason.OUTPUT_PROTECTED);
            assertThat(e.retryable()).isFalse();
        });
        verifyNoInteractions(storage, http);
    }

    private VideoWorkflow workflow() {
        var workflow = new VideoWorkflow();
        workflow.setId(7L);
        workflow.setTenantId(3L);
        workflow.setProviderResultUrl("https://example.com/video.mp4");
        return workflow;
    }

    @Test
    void failedDeletionPreservesTheOriginalImportErrorAndItsRegisteredKey() throws Exception {
        var storage = mock(ObjectStorage.class);
        var httpClient = mock(HttpClient.class);
        when(httpClient.execute(any(HttpGet.class), any(HttpClientResponseHandler.class))).thenThrow(new java.io.IOException("download failed"));
        doThrow(new IllegalStateException("MinIO offline")).when(storage).delete(anyString());
        var service = service(storage, httpClient);
        var workflow = new VideoWorkflow();
        workflow.setId(7L);
        workflow.setTenantId(3L);
        workflow.setProviderResultUrl("https://example.com/video.mp4");
        String key = "t3/generated-video/7/import-00000000-0000-0000-0000-000000000001/output.mp4";
        workflow.setPendingCleanupKeys(java.util.List.of(key));
        assertThatThrownBy(() -> service.importUrl(workflow, key)).hasRootCauseMessage("download failed");
        assertThat(workflow.getPendingCleanupKeys()).containsExactly(key);
        verify(storage).delete(key);
    }

    @Test
    void importsVideoByStreamingWithoutCreatingAByteArrayCopy() throws Exception {
        var storage = mock(ObjectStorage.class);
        var httpClient = mock(HttpClient.class);
        var response = mock(ClassicHttpResponse.class);
        byte[] bytes = new byte[128];
        bytes[4] = 'f'; bytes[5] = 't'; bytes[6] = 'y'; bytes[7] = 'p';
        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(new ByteArrayEntity(bytes, ContentType.create("video/mp4")));
        when(httpClient.execute(any(HttpGet.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response));
        AtomicLong consumed = new AtomicLong();
        doAnswer(invocation -> {
            var input = (java.io.InputStream) invocation.getArgument(1);
            consumed.set(input.transferTo(java.io.OutputStream.nullOutputStream()));
            return null;
        }).when(storage).put(anyString(), any(java.io.InputStream.class), anyLong(), anyString());

        var service = service(storage, httpClient);
        ReflectionTestUtils.setField(service, "maxProviderBytes", 1024L);
        var workflow = new VideoWorkflow();
        workflow.setId(7L);
        workflow.setTenantId(3L);
        workflow.setProviderResultUrl("https://example.com/video.mp4");
        var imported = service.importUrl(workflow);

        assertThat(imported.sizeBytes()).isEqualTo(bytes.length);
        assertThat(consumed).hasValue(bytes.length);
        verify(storage).put(anyString(), any(java.io.InputStream.class), eq((long) bytes.length), eq("video/mp4"));
    }

    @Test
    void rejectsAnOversizedProviderResponseBeforePersistingIt() throws Exception {
        var storage = mock(ObjectStorage.class);
        var httpClient = mock(HttpClient.class);
        var response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(new ByteArrayEntity(new byte[32], ContentType.create("video/mp4")));
        when(httpClient.execute(any(HttpGet.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response));
        var service = service(storage, httpClient);
        ReflectionTestUtils.setField(service, "maxProviderBytes", 8L);
        var workflow = new VideoWorkflow();
        workflow.setId(7L);
        workflow.setTenantId(3L);
        workflow.setProviderResultUrl("https://example.com/video.mp4");

        assertThatThrownBy(() -> service.importUrl(workflow)).hasMessageContaining("保存供应商视频失败");
        verify(storage, never()).put(anyString(), any(java.io.InputStream.class), anyLong(), anyString());
    }

    private VideoWorkflowService service(ObjectStorage storage, HttpClient httpClient) {
        return new VideoWorkflowService(mock(VideoWorkflowRepository.class), mock(VideoProviderJobRepository.class),
                mock(AssetRepository.class), mock(AssetService.class), storage, mock(TaskService.class),
                new ObjectMapper(), mock(TenantRateLimiter.class), httpClient, mock(VideoMediaProbe.class),
                mock(com.wuyao.growth.common.metrics.VideoMetrics.class));
    }
}
