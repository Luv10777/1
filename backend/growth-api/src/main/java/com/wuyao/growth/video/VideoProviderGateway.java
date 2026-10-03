package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OnlyRouter/OpenAI-compatible provider boundary: submit returns a remote job id,
 * poll returns a state, and completed jobs expose a 302 content URL.
 * The provider's URL is never exposed by the controller.
 */
@Component
@RequiredArgsConstructor
public class VideoProviderGateway {
    private final ObjectMapper json;
    private final AssetService assets;

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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", providerModel(request.model()));
        body.put("seconds", request.durationSeconds());
        body.put("resolution", request.resolution());
        String prompt = request.prompt().isBlank() ? "" : request.prompt() + "\n画幅：" + request.ratio();
        body.put("mode", request.referenceVideoAssetId() != null ? "video-to-video"
                : request.referenceImageAssetIds().isEmpty() ? "text-to-video" : "image-to-video");
        if (!request.referenceImageAssetIds().isEmpty()) {
            var content = new java.util.ArrayList<Map<String, Object>>();
            if (!prompt.isBlank()) content.add(Map.of("type", "text", "text", prompt));
            for (Long assetId : request.referenceImageAssetIds()) {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", assets.presignedReference(assetId, "IMAGE"))));
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
            input.put("duration", reference.durationMs() == null ? request.durationSeconds() : Math.max(1, reference.durationMs() / 1000));
            body.put("input_reference", input);
        }
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

    public String protocol() { return protocol; }

    private JsonNode send(String method, String path, Map<String, Object> body, String idempotencyKey) {
        try {
            String url = baseUrl.replaceAll("/$", "") + (path.startsWith("/") ? path : "/" + path);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header("Idempotency-Key", idempotencyKey == null ? "" : idempotencyKey)
                    .header(HttpHeaders.ACCEPT, "application/json");
            if ("POST".equals(method)) {
                builder.header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                builder.GET();
            }
            HttpResponse<String> result = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() / 100 != 2) throw providerError("供应商响应 HTTP " + result.statusCode());
            return json.readTree(result.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, "视频供应商请求失败，请稍后重试");
        }
    }

    private String resolveContentUrl(String jobId) {
        try {
            String url = baseUrl.replaceAll("/$", "") + "/" + contentPath.replace("{jobId}", jobId).replaceFirst("^/", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).GET().build();
            HttpResponse<Void> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300 && response.statusCode() < 400 && response.headers().firstValue("location").isPresent()) {
                return response.headers().firstValue("location").orElseThrow();
            }
            if (response.statusCode() / 100 == 2) return response.headers().firstValue("location").orElse(null);
            throw providerError("获取视频内容地址失败，HTTP " + response.statusCode());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, "获取视频内容地址失败");
        }
    }

    private void requireConfigured() {
        if (!configured()) throw BizException.of(ErrorCode.VIDEO_NOT_CONFIGURED, "视频供应商尚未配置，请联系管理员");
    }

    private BizException providerError(String message) { return BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, message); }

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

    public record SubmitResult(String provider, String providerJobId, String status) {}
    public record PollResult(String status, String resultUrl, String error) {}
}
