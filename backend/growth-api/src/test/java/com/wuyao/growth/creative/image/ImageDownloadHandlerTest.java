package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.gateway.ImageModelProperties;
import com.wuyao.growth.common.metrics.ImageMetrics;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageDownloadHandlerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void filesApiRedirectRequiresAllowedTargetAndNeverForwardsApiKey(boolean allowTarget) throws Exception {
        var renderer = new ImageRenderer();
        byte[] source = renderer.png(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
        var client = mock(HttpClient.class);
        String fileUrl = "https://api.onlyrouter.ai/v1/files/file-123/content";
        String signedUrl = "https://storage.example.com/result.png?signature=private";
        var requests = new ArrayList<ClassicHttpRequest>();
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
            .thenAnswer(invocation -> {
                var request = (ClassicHttpRequest) invocation.getArgument(0);
                requests.add(request);
                var response = mock(ClassicHttpResponse.class);
                if (requests.size() == 1) {
                    when(response.getCode()).thenReturn(302);
                    when(response.getFirstHeader("Location")).thenReturn(new BasicHeader("Location", signedUrl));
                } else {
                    when(response.getCode()).thenReturn(200);
                    when(response.getEntity()).thenReturn(new ByteArrayEntity(source, ContentType.IMAGE_PNG));
                }
                return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
            });
        var config = new ImageModelProperties();
        config.getGenerator().setApiKey("test-key");
        config.getGenerator().setDownloadAllowedOrigins(List.of("https://api.onlyrouter.ai", "https://storage.example.com"));
        if (!allowTarget) config.getGenerator().setDownloadAllowedOrigins(List.of("https://api.onlyrouter.ai"));
        var service = mock(ImageCreationService.class);
        var item = new ImageItem();
        item.setCreationId(5L);
        var creation = new ImageCreation();
        creation.setRequest(new ImageDtos.Create("test-request", "PRODUCT_SET", "product", List.of(),
            "1:1", "1080P", 1, "LOCAL", "PRODUCT_MAIN", "test", "test", null));
        when(service.itemSnapshot(8L)).thenReturn(item);
        when(service.snapshot(5L)).thenReturn(creation);
        var task = new Task();
        task.setPayload(Map.of("itemId", 8L, "imageUrl", fileUrl,
            "targetKey", "t2/generated/8/0/background.png"));

        var handler = new ImageDownloadHandler(mock(ObjectStorage.class), service, renderer, client,
            config, mock(ImageMetrics.class));
        if (!allowTarget) {
            assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(RuntimeException.class);
            assertThat(requests).hasSize(1);
            verifyNoInteractions(service);
            return;
        }
        assertThat(handler.handle(task)).containsEntry("status", "DOWNLOADED");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).getFirstHeader("Authorization").getValue()).isEqualTo("Bearer test-key");
        assertThat(requests.get(1).getUri().toString()).isEqualTo(signedUrl);
        assertThat(requests.get(1).getFirstHeader("Authorization")).isNull();
    }

    @Test
    void limitsChunkedResponseWhileStreamingToStorage() throws Exception {
        var client = mock(HttpClient.class);
        var entity = mock(org.apache.hc.core5.http.HttpEntity.class);
        when(entity.getContentLength()).thenReturn(-1L);
        var produced = new java.util.concurrent.atomic.AtomicLong();
        when(entity.getContent()).thenReturn(new java.io.InputStream() {
            public int read() { produced.incrementAndGet(); return 0; }
            public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 0);
                produced.addAndGet(length);
                return length;
            }
        });
        var response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(entity);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
            .thenAnswer(invocation -> ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response));
        var storage = mock(ObjectStorage.class);
        doAnswer(invocation -> {
            ((java.io.InputStream) invocation.getArgument(1)).transferTo(java.io.OutputStream.nullOutputStream());
            return null;
        }).when(storage).put(anyString(), any(java.io.InputStream.class), anyLong(), anyString());
        var config = new ImageModelProperties();
        config.getGenerator().setDownloadAllowedOrigins(List.of("https://storage.example.com"));
        var service = mock(ImageCreationService.class);
        var handler = new ImageDownloadHandler(storage, service, new ImageRenderer(), client, config, mock(ImageMetrics.class));
        var task = new Task();
        task.setPayload(Map.of("itemId", 8L, "imageUrl", "https://storage.example.com/image.png", "targetKey", "target"));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(RuntimeException.class);
        assertThat(produced.get()).isBetween(64 * 1024 * 1024L + 1, 64 * 1024 * 1024L + 8192);
        verifyNoInteractions(service);
    }

    @Test
    void downloadsAndPersistsAProviderImage() throws Exception {
        var renderer = new ImageRenderer();
        var image = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
        byte[] source = renderer.png(image);
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var pool = Executors.newSingleThreadExecutor();
             var client = HttpClients.custom().disableRedirectHandling().build()) {
            String origin = "http://127.0.0.1:" + server.getLocalPort();
            var request = pool.submit(() -> {
                try (var socket = server.accept()) {
                    var input = socket.getInputStream();
                    var header = new java.io.ByteArrayOutputStream();
                    while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) header.write(input.read());
                    var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: "
                        + source.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(source);
                    output.flush();
                    return header.toString(StandardCharsets.US_ASCII);
                }
            });
            var config = new ImageModelProperties();
            config.getGenerator().setDownloadAllowedOrigins(List.of(origin));
            var storage = mock(ObjectStorage.class);
            var service = mock(ImageCreationService.class);
            var metrics = mock(ImageMetrics.class);
            var item = new ImageItem();
            item.setCreationId(5L);
            var creation = new ImageCreation();
            creation.setRequest(new ImageDtos.Create("test-request", "PRODUCT_SET", "product", List.of(),
                "1:1", "1080P", 1, "LOCAL", "PRODUCT_MAIN", "test", "test", null));
            when(service.itemSnapshot(8L)).thenReturn(item);
            when(service.snapshot(5L)).thenReturn(creation);
            var task = new Task();
            task.setPayload(Map.of("itemId", 8L, "imageUrl", origin + "/result.png",
                "targetKey", "t2/generated/8/0/background.png"));

            var handler = new ImageDownloadHandler(storage, service, renderer, client, config, metrics);
            assertThat(handler.handle(task)).containsEntry("status", "DOWNLOADED");
            assertThat(request.get(5, TimeUnit.SECONDS)).startsWith("GET /result.png ").doesNotContain("Authorization");
            verify(storage).put(eq("t2/generated/8/0/background.png"), any(byte[].class), eq("image/png"));
            verify(service).markImagePersisted(8L, "t2/generated/8/0/background.png", 48, 32, null);
        }
    }
}
