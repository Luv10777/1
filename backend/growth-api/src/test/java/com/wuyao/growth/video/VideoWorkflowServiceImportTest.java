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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoWorkflowServiceImportTest {
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
                new ObjectMapper(), mock(TenantRateLimiter.class), httpClient, mock(VideoMediaProbe.class));
    }
}
