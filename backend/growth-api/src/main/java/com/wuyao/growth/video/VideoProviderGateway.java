package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Asynchronous video provider boundary. Volcengine tasks return their video URL in
 * the polling response; the legacy OpenAI video endpoint exposes a content redirect.
 * The provider's URL is never exposed by the controller.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoProviderGateway {
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final ObjectMapper json;
    private final AssetService assets;
    private final HttpClient httpClient;

    @Value("${growth.video.provider.base-url:https://haomaotoken.com}") private String baseUrl;
    @Value("${growth.video.provider.api-key:}") private String apiKey;
    @Value("${growth.video.provider.api-key-seedance-2-5:}") private String seedance25ApiKey;
    @Value("${growth.video.provider.api-key-seedance-2-0:}") private String seedance20ApiKey;
    @Value("${growth.video.provider.api-key-seedance-2-0-mini:}") private String seedance20MiniApiKey;
    @Value("${growth.video.provider.api-key-seedance-2-0-fast:}") private String seedance20FastApiKey;
    @Value("${growth.video.provider.submit-path:/api/v3/contents/generations/tasks}") private String submitPath;
    @Value("${growth.video.provider.poll-path:/api/v3/contents/generations/tasks/{jobId}}") private String pollPath;
    @Value("${growth.video.provider.content-path:/v1/videos/{jobId}/content}") private String contentPath;
    @Value("${growth.video.provider.name:haomaotoken}") private String providerName;
    @Value("${growth.video.provider.protocol:VOLCENGINE}") private String protocol;
    @Value("${growth.video.provider.timeout-seconds:60}") private long timeoutSeconds;
    @Value("${growth.video.model.seedance-2-5:seedance-2.5}") private String seedance25;
    @Value("${growth.video.model.seedance-2-0:seedance-2.0}") private String seedance20;
    @Value("${growth.video.model.seedance-2-0-mini:seedance-2.0-mini}") private String seedance20Mini;
    @Value("${growth.video.model.seedance-2-0-fast:seedance-2.0-fast}") private String seedance20Fast;

    public boolean configured() {
        return hasText(baseUrl) && (hasText(apiKey) || hasText(seedance25ApiKey)
                || hasText(seedance20ApiKey) || hasText(seedance20MiniApiKey) || hasText(seedance20FastApiKey));
    }

    public SubmitResult submit(VideoDtos.Create request, String idempotencyKey) {
        requireConfigured();
        return submit(buildSubmitRequest(request), idempotencyKey);
    }

    /** Refresh references only while the workflow is known never to have dispatched a request. */
    public Map<String, Object> buildSubmitRequest(VideoDtos.Create request) {
        if (volcengine()) return buildVolcengineRequest(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", providerModel(request.model()));
        body.put("seconds", request.durationSeconds());
        body.put("resolution", request.resolution());
        String prompt = request.prompt();
        if (!prompt.isBlank() && !"auto".equals(request.ratio())) prompt += "\n画幅：" + request.ratio();
        body.put("mode", request.referenceVideoAssetId() != null ? "video-to-video"
                : request.referenceImageAssetIds().isEmpty() ? "text-to-video" : "image-to-video");
        if (!request.referenceImageAssetIds().isEmpty()) {
            var content = new ArrayList<Map<String, Object>>();
            if (!prompt.isBlank()) content.add(Map.of("type", "text", "text", prompt));
            for (Long assetId : request.referenceImageAssetIds()) {
                content.add(Map.of("type", "image_url", "role", "reference_image",
                        "image_url", Map.of("url", assets.presignedReference(assetId, "IMAGE"))));
            }
            body.put("content", content);
        } else if (!prompt.isBlank()) {
            body.put("prompt", prompt);
        }
        if (request.referenceVideoAssetId() != null) {
            var reference = assets.mediaReference(request.referenceVideoAssetId(), "VIDEO");
            var input = new LinkedHashMap<String, Object>();
            input.put("type", "video");
            input.put("url", assets.presignedReference(request.referenceVideoAssetId(), "VIDEO"));
            input.put("duration", Math.max(1, reference.durationMs() / 1000));
            body.put("input_reference", input);
        }
        return body;
    }

    private Map<String, Object> buildVolcengineRequest(VideoDtos.Create request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", providerModel(request.model()));
        var content = new ArrayList<Map<String, Object>>();
        if (!request.prompt().isBlank()) content.add(Map.of("type", "text", "text", request.prompt()));
        for (Long assetId : request.referenceImageAssetIds()) {
            content.add(Map.of("type", "image_url", "role", "reference_image",
                    "image_url", Map.of("url", assets.presignedReference(assetId, "IMAGE"))));
        }
        if (request.referenceVideoAssetId() != null) {
            content.add(Map.of("type", "video_url", "role", "reference_video",
                    "video_url", Map.of("url", assets.presignedReference(request.referenceVideoAssetId(), "VIDEO"))));
        }
        body.put("content", content);
        body.put("duration", request.durationSeconds());
        if (!"auto".equals(request.ratio())) body.put("ratio", request.ratio());
        body.put("resolution", request.resolution().toLowerCase(Locale.ROOT));
        return body;
    }

    public SubmitResult submit(Map<String, Object> body, String idempotencyKey) {
        try {
            return submit(json.writeValueAsString(body), idempotencyKey);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw providerError("视频供应商请求无法序列化");
        }
    }

    /** Send the exact persisted JSON bytes; reserializing JSONB can change key order. */
    public SubmitResult submit(String body, String idempotencyKey) {
        requireConfigured();
        String key = submissionApiKey(body);
        JsonNode response = send("POST", submitPath, body, idempotencyKey, key);
        String jobId = firstText(response, "id", "task_id", "job_id");
        if (jobId == null) throw providerError("供应商未返回任务 ID");
        return new SubmitResult(providerName, jobId, normalizeStatus(firstText(response, "status", "state")));
    }

    public PollResult poll(String jobId, String model, String idempotencyKey) {
        requireConfigured();
        String key = apiKeyForModel(model);
        JsonNode response = send("GET", pollPath.replace("{jobId}", jobId), null, idempotencyKey, key);
        String status = normalizeStatus(firstText(response, "status", "state"));
        String url = "SUCCEEDED".equals(status)
                ? (volcengine() ? resultVideoUrl(response) : resolveContentUrl(jobId, key)) : null;
        JsonNode usage=response.path("usage");
        if(usage.isMissingNode()) usage=response.path("data").path("usage");
        Long tokens=usage.path("completion_tokens").asLong(0)>0?usage.path("completion_tokens").asLong():null;
        if(tokens==null && usage.path("total_tokens").asLong(0)>0) tokens=usage.path("total_tokens").asLong();
        return new PollResult(status, url, errorMessage(response),tokens);
    }

    public String providerName() { return providerName; }

    public String protocol() { return protocol; }

    private JsonNode send(String method, String path, String body, String idempotencyKey, String key) {
        try {
            URI endpoint = VideoUrlSecurity.checkedHttps(joinUrl(path));
            var request = "POST".equals(method) ? new HttpPost(endpoint) : new HttpGet(endpoint);
            request.setConfig(requestConfig());
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key);
            request.setHeader(HttpHeaders.ACCEPT, "application/json");
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                request.setHeader("Idempotency-Key", idempotencyKey);
            }
            if (request instanceof HttpPost post) {
                post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
            }
            return httpClient.execute(request, response -> {
                int status = response.getCode();
                String responseBody = readBody(response.getEntity(), MAX_RESPONSE_BYTES);
                if (status / 100 != 2) {
                    String detail = providerErrorDetail(responseBody);
                    log.warn("视频供应商请求失败: method={} path={} status={} detail={}", method, path, status, detail);
                    throw new ProviderHttpException(status,
                            "供应商响应 HTTP " + status + (detail == null ? "" : ": " + detail));
                }
                return json.readTree(responseBody);
            });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("视频供应商请求异常: method={} path={} type={}", method, path, e.getClass().getSimpleName());
            throw BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, "视频供应商请求失败，请稍后重试");
        }
    }

    private String resolveContentUrl(String jobId, String key) {
        try {
            URI current = VideoUrlSecurity.checkedHttps(joinUrl(contentPath.replace("{jobId}", jobId)));
            String providerHost = current.getHost();
            for (int redirects = 0; redirects <= 5; redirects++) {
                var request = new HttpGet(current);
                request.setConfig(requestConfig());
                if (providerHost.equalsIgnoreCase(current.getHost())) {
                    request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key);
                }
                RedirectResponse response = httpClient.execute(request, result -> {
                    int status = result.getCode();
                    String location = result.getFirstHeader(HttpHeaders.LOCATION) == null ? null
                            : result.getFirstHeader(HttpHeaders.LOCATION).getValue();
                    EntityUtils.consumeQuietly(result.getEntity());
                    return new RedirectResponse(status, location);
                });
                if (response.status() / 100 == 2) {
                    if (!providerHost.equalsIgnoreCase(current.getHost())) return current.toString();
                    throw providerError("获取视频内容地址失败，供应商未返回可下载地址");
                }
                if (response.status() < 300 || response.status() >= 400) {
                    throw new ProviderHttpException(response.status(), "获取视频内容地址失败，HTTP " + response.status());
                }
                if (response.location() == null || response.location().isBlank()) {
                    throw providerError("获取视频内容地址失败，HTTP " + response.status());
                }
                URI next = VideoUrlSecurity.checkedHttps(current.resolve(response.location()).toString());
                if (!providerHost.equalsIgnoreCase(next.getHost())) return next.toString();
                current = next;
            }
            throw providerError("获取视频内容地址失败，重定向次数过多");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("获取视频内容地址异常: type={}", e.getClass().getSimpleName());
            throw BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, "获取视频内容地址失败");
        }
    }

    private String resultVideoUrl(JsonNode response) {
        String url = videoUrl(response.path("content"));
        if (url == null) url = videoUrl(response.path("data").path("content"));
        if (url == null) throw providerError("供应商已完成但未返回视频 URL");
        return VideoUrlSecurity.checkedHttps(url).toString();
    }

    private String videoUrl(JsonNode content) {
        if (content.isArray()) {
            for (JsonNode item : content) {
                String url = videoUrl(item);
                if (url != null) return url;
            }
        } else if (content.isObject()) {
            JsonNode video = content.path("video_url");
            if (video.isTextual() && hasText(video.asText())) return video.asText();
            if (video.isObject()) return firstText(video, "url");
        }
        return null;
    }

    private String submissionApiKey(String body) {
        if (!volcengine()) return requireApiKey(apiKey);
        try {
            String model = json.readTree(body).path("model").asText();
            for (String alias : new String[]{"SEEDANCE_2_5", "SEEDANCE_2_0", "SEEDANCE_2_0_MINI", "SEEDANCE_2_0_FAST"}) {
                if (providerModel(alias).equals(model)) return apiKeyForModel(alias);
            }
            throw providerError("不支持的视频供应商模型");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw providerError("视频供应商请求无法解析");
        }
    }

    private String apiKeyForModel(String model) {
        String override = switch (model) {
            case "SEEDANCE_2_5" -> seedance25ApiKey;
            case "SEEDANCE_2_0" -> seedance20ApiKey;
            case "SEEDANCE_2_0_MINI" -> seedance20MiniApiKey;
            case "SEEDANCE_2_0_FAST" -> seedance20FastApiKey;
            default -> throw providerError("不支持的视频模型: " + model);
        };
        return requireApiKey(hasText(override) ? override : apiKey);
    }

    private String requireApiKey(String key) {
        if (!hasText(key)) throw BizException.of(ErrorCode.VIDEO_NOT_CONFIGURED, "视频模型密钥尚未配置，请联系管理员");
        return key;
    }

    private boolean volcengine() { return "VOLCENGINE".equalsIgnoreCase(protocol); }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private RequestConfig requestConfig() {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .setConnectTimeout(Timeout.ofSeconds(15))
                .setResponseTimeout(Timeout.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
    }

    private String joinUrl(String path) {
        return baseUrl.replaceAll("/$", "") + "/" + path.replaceFirst("^/", "");
    }

    private String readBody(HttpEntity entity, int limit) throws IOException {
        if (entity == null) return "";
        if (entity.getContentLength() > limit) throw new IOException("供应商响应过大");
        try (var input = entity.getContent()) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("供应商响应过大");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void requireConfigured() {
        if (!configured()) throw BizException.of(ErrorCode.VIDEO_NOT_CONFIGURED, "视频供应商尚未配置，请联系管理员");
        if (!volcengine() && !"CHAT_COMPLETIONS".equalsIgnoreCase(protocol)) {
            throw BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, "视频供应商协议暂不支持: " + protocol);
        }
    }

    private BizException providerError(String message) { return BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, message); }

    /** HTTP validation/conflict errors cannot be repaired by replaying the same task. */
    public static final class ProviderHttpException extends BizException {
        private final int status;

        public ProviderHttpException(int status, String message) {
            super(ErrorCode.VIDEO_PROVIDER_ERROR, message);
            this.status = status;
        }

        public int status() { return status; }

        public boolean retryable() {
            return status == 408 || status == 429 || status >= 500;
        }
    }

    private String providerErrorDetail(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            String detail = errorMessage(json.readTree(body));
            return detail == null || detail.isBlank() ? null : detail.substring(0, Math.min(detail.length(), 300));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeStatus(String status) {
        if (status == null) return "RUNNING";
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "DONE" -> "SUCCEEDED";
            case "FAIL", "FAILED", "ERROR", "EXPIRED" -> "FAILED";
            case "CANCELLED", "CANCELED" -> "CANCELLED";
            case "QUEUED", "PENDING" -> "QUEUED";
            default -> "RUNNING";
        };
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) if (node.hasNonNull(name) && !node.get(name).asText().isBlank()) return node.get(name).asText();
        JsonNode data = node.get("data");
        if (data != null && data.isObject()) return firstText(data, names);
        return null;
    }

    public String baseUrl() { return baseUrl; }
    public String providerModel(String model) {
        return switch (model) {
            case "SEEDANCE_2_5" -> seedance25;
            case "SEEDANCE_2_0" -> seedance20;
            case "SEEDANCE_2_0_MINI" -> seedance20Mini;
            case "SEEDANCE_2_0_FAST" -> seedance20Fast;
            default -> throw providerError("不支持的视频模型: " + model);
        };
    }

    private String errorMessage(JsonNode node) {
        JsonNode error = node.get("error");
        if (error != null && error.isObject()) {
            String code = firstText(error, "code");
            String message = firstText(error, "message");
            return redactCredentials(code == null ? message : code + (message == null ? "" : ": " + message));
        }
        if (node.path("data").isObject()) return errorMessage(node.path("data"));
        return redactCredentials(firstText(node, "message"));
    }

    private String redactCredentials(String message) {
        if (message == null) return null;
        for (String key : new String[]{apiKey, seedance25ApiKey, seedance20ApiKey, seedance20MiniApiKey, seedance20FastApiKey}) {
            if (hasText(key)) message = message.replace(key, "[redacted]");
        }
        return message;
    }

    private record RedirectResponse(int status, String location) {}

    public record SubmitResult(String provider, String providerJobId, String status) {}
    public record PollResult(String status, String resultUrl, String error, Long billingTokens) {
        public PollResult(String status,String resultUrl,String error) { this(status,resultUrl,error,null); }
    }
}
