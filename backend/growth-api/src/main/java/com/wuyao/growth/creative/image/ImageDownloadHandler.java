package com.wuyao.growth.creative.image;

import com.wuyao.growth.common.gateway.ImageModelProperties;
import com.wuyao.growth.common.gateway.ImageDownloadOrigins;
import com.wuyao.growth.common.metrics.ImageMetrics;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.Map;

/** Downloads a short-lived provider result outside the IMAGE generation queue. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageDownloadHandler implements TaskHandler {
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private final ObjectStorage storage;
    private final ImageCreationService service;
    private final ImageRenderer renderer;
    private final HttpClient httpClient;
    private final ImageModelProperties config;
    private final ImageMetrics metrics;

    @Override
    public String type() {
        return "IMAGE_DOWNLOAD";
    }

    @Override
    public Map<String, Object> handle(Task task) {
        long started = System.nanoTime();
        long itemId = ((Number) task.getPayload().get("itemId")).longValue();
        String imageUrl = (String) task.getPayload().get("imageUrl");
        String targetKey = (String) task.getPayload().get("targetKey");
        try {
            byte[] streamed = transferToStorage(imageUrl, targetKey);
            // Read only after the stream is durable so the image can be
            // validated and dimensions can be persisted without a temp file.
            byte[] bytes = storage.read(targetKey, MAX_BYTES);
            // Mockito and small legacy storage adapters may not implement the
            // streaming overload. The captured bytes are only a compatibility
            // fallback; MinIO consumes the stream directly in production.
            if ((bytes == null || bytes.length == 0) && streamed != null) bytes = streamed;
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("对象存储写入后无法读取图片");
            var image = renderer.decode(bytes);
            var item = service.itemSnapshot(itemId);
            var creation = service.snapshot(item.getCreationId());
            String imageHash = "POSTER".equals(creation.getRequest().workflow())
                && (creation.getParentId() == null || creation.getVariation() != null)
                ? ImageFingerprint.of(image) : null;
            service.markImagePersisted(itemId, targetKey, image.getWidth(), image.getHeight(), imageHash);
            log.info("图片下载完成: itemId={} key={} sizeBytes={}", itemId, targetKey, bytes.length);
            metrics.recordDownload((System.nanoTime() - started) / 1_000_000, "succeeded");
            return Map.of("status", "DOWNLOADED", "key", targetKey, "sizeBytes", bytes.length);
        } catch (Exception e) {
            metrics.recordDownload((System.nanoTime() - started) / 1_000_000, "failed");
            log.warn("图片下载失败: itemId={} cause={}", itemId, e.getClass().getSimpleName());
            throw new RuntimeException("下载图片失败");
        }
    }

    private byte[] transferToStorage(String value, String targetKey) throws Exception {
        URI source = ImageDownloadOrigins.checkedUri(value);
        if (!ImageDownloadOrigins.allows(source, config.getGenerator().getDownloadAllowedOrigins()))
            throw new IllegalArgumentException("图片下载地址不在允许的来源范围内");
        HttpGet first = new HttpGet(source);
        if (isOnlyRouterFilesContent(source)) {
            if (!"https".equalsIgnoreCase(source.getScheme()))
                throw new IllegalArgumentException("Files API 地址必须使用 HTTPS");
            first.setHeader("Authorization", "Bearer " + config.getGenerator().getApiKey());
        }
        return httpClient.execute(first, (HttpClientResponseHandler<byte[]>) response -> {
            int status = response.getCode();
            if (status >= 300 && status < 400) {
                String location = response.getFirstHeader("Location") == null ? null
                    : response.getFirstHeader("Location").getValue();
                if (location == null || location.isBlank()) throw new IllegalStateException("下载响应缺少重定向地址");
                URI redirect = ImageDownloadOrigins.checkedUri(location);
                if (!"https".equalsIgnoreCase(redirect.getScheme()))
                    throw new IllegalArgumentException("图片重定向地址必须使用 HTTPS");
                // The signed URL is issued by the provider; never forward the API key to it.
                return httpClient.execute(new HttpGet(redirect), (HttpClientResponseHandler<byte[]>) redirected -> {
                    return storeResponse(redirected, targetKey);
                });
            }
            return storeResponse(response, targetKey);
        });
    }

    private byte[] storeResponse(ClassicHttpResponse response, String targetKey) throws java.io.IOException {
        if (response.getCode() < 200 || response.getCode() >= 300)
            throw new IllegalStateException("下载图片失败: HTTP " + response.getCode());
        var entity = response.getEntity();
        if (entity == null) throw new IllegalStateException("下载响应为空");
        if (entity.getContentLength() > MAX_BYTES) throw new IllegalArgumentException("图片文件过大");
        try (var input = entity.getContent()) {
            final long[] consumed = {0};
            InputStream tracked = new FilterInputStream(input) {
                @Override public int read() throws java.io.IOException {
                    int value = super.read();
                    if (value >= 0) consumed[0]++;
                    return value;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                    int count = super.read(buffer, offset, length);
                    if (count > 0) consumed[0] += count;
                    return count;
                }
            };
            String contentType = entity.getContentType() == null ? "application/octet-stream" : entity.getContentType();
            storage.put(targetKey, tracked, entity.getContentLength(), contentType);
            // A non-streaming test/legacy adapter may return without consuming
            // the input. Complete that adapter through its byte[] contract.
            if (consumed[0] == 0 && entity.getContentLength() != 0) {
                byte[] remaining = input.readAllBytes();
                if (remaining.length > MAX_BYTES) throw new IllegalArgumentException("图片文件过大");
                storage.put(targetKey, remaining, contentType);
                return remaining;
            }
            return null;
        }
    }

    private boolean isOnlyRouterFilesContent(URI uri) {
        return "api.onlyrouter.ai".equalsIgnoreCase(uri.getHost())
            && uri.getPath() != null && uri.getPath().matches("/v1/files/[^/]+/content");
    }

}
