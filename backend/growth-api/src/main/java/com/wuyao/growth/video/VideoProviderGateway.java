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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OnlyRouter/OpenAI-compatible provider boundary: submit returns a remote job id,
 * poll returns a state, and completed jobs expose a 302 content URL.
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

    @Value("${growth.video.provider.base-url:https://api.onlyrouter.ai/v1}") private String baseUrl;
    @Value("${growth.video.provider.api-key:}") private String apiKey;
    @Value("${growth.video.provider.submit-path:/videos}") private String submitPath;
    @Value("${growth.video.provider.poll-path:/videos/{jobId}}") private String pollPath;
    @Value("${growth.video.provider.content-path:/videos/{jobId}/content}") private String contentPath;
    @Value("${growth.video.provider.name:seedance}") private String providerName;
    @Value("${growth.video.provider.protocol:CHAT_COMPLETIONS}") private String protocol;
    @Value("${growth.video.provider.timeout-seconds:60}") private long timeoutSeconds;
    @Value("${growth.video.model.seedance-2-5:doubao-seedance-2-5-260128}") private String seedance25;
    @Value("${growth.video.model.seedance-2-0:doubao-seedance-2-0-260128}") private String seedance20;
    @Value("${growth.video.model.seedance-2-0-mini:doubao-seedance-2-0-mini-260128}") private String seedance20Mini;
    @Value("${growth.video.model.seedance-2-0-fast:doubao-seedance-2-0-fast-260128}") private String seedance20Fast;

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    public SubmitResult submit(VideoDtos.Create request, String idempotencyKey) {
        requireConfigured();
        return submit(buildSubmitRequest(request), idempotencyKey);
    }

    /** Build once and persist this request before the first provider call. */
    public Map<String, Object> buildSubmitRequest(VideoDtos.Create request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", providerModel(request.model()));
        body.put("seconds", request.durationSeconds());
        body.put("resolution", request.resolution());
        String prompt = request.prompt();
        if (!prompt.isBlank() && !"auto".equals(request.ratio())) prompt += "\n画幅：" + request.ratio();
        body.put("mode", request.referenceVideoAssetId() != null ? "video-to-video"
                : request.referenceImageAssetIds().isEmpty() ? "text-to-video" : "image-to-video");
        if (!request.referenceImageAssetIds().isEmpty()) {
            var content = new java.util.ArrayList<Map<String, Object>>();
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

    public SubmitResult submit(Map<String, Object> body, String idempotencyKey) {
        requireConfigured();
        JsonNode response = send("POST", submitPath, body, idempotencyKey);
        String jobId = firstText(response, "id", "task_id", "job_id");
        if (jobId == null) throw providerError("供应商未返回任务 ID");
        return new SubmitResult(providerName, jobId, normalizeStatus(firstText(response, "status", "state")));
    }

    public PollResult poll(String jobId, String idempotencyKey) {
        requireConfigured();
        JsonNode response = send("GET", pollPath.replace("{jobId}", jobId), null, idempotencyKey);
        String status = normalizeStatus(firstText(response, "status", "state"));
        String url = "SUCCEEDED".equals(status) ? resolveContentUrl(jobId) : null;
        return new PollResult(status, url, errorMessage(response));
    }

    public String providerName() { return providerName; }

    /** The only protocol currently implemented by this gateway. */
    public String protocol() { return protocol; }

    private JsonNode send(String method, String path, Map<String, Object> body, String idempotencyKey) {
        try {
            URI endpoint = VideoUrlSecurity.checkedHttps(joinUrl(path));
            if (!"CHAT_COMPLETIONS".equalsIgnoreCase(protocol)) {
                throw providerError("视频供应商协议暂不支持: " + protocol);
            }
            var request = "POST".equals(method) ? new HttpPost(endpoint) : new HttpGet(endpoint);
            request.setConfig(requestConfig());
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            request.setHeader(HttpHeaders.ACCEPT, "application/json");
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                request.setHeader("Idempotency-Key", idempotencyKey);
            }
            if (request instanceof HttpPost post) {
                post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                post.setEntity(new StringEntity(json.writeValueAsString(body), ContentType.APPLICATION_JSON));
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

    private String resolveContentUrl(String jobId) {
        try {
            URI current = VideoUrlSecurity.checkedHttps(joinUrl(contentPath.replace("{jobId}", jobId)));
            String providerHost = current.getHost();
            for (int redirects = 0; redirects <= 5; redirects++) {
                var request = new HttpGet(current);
                request.setConfig(requestConfig());
                if (providerHost.equalsIgnoreCase(current.getHost())) {
                    request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
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
                if (response.status() < 300 || response.status() >= 400
                        || response.location() == null || response.location().isBlank()) {
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
        if (!"CHAT_COMPLETIONS".equalsIgnoreCase(protocol)) {
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
        return switch (status.toUpperCase()) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "DONE" -> "SUCCEEDED";
            case "FAIL", "FAILED", "ERROR" -> "FAILED";
            case "CANCELLED", "CANCELED" -> "CANCELED";
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

    private String providerModel(String model) {
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
            return code == null ? message : code + (message == null ? "" : ": " + message);
        }
        return firstText(node, "message");
    }

    private record RedirectResponse(int status, String location) {}

    public record SubmitResult(String provider, String providerJobId, String status) {}
    public record PollResult(String status, String resultUrl, String error) {}
}
