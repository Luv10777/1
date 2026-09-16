package com.wuyao.vimax.service.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ToAPIs Seedance 2.0 视频生成 Adapter
 *
 * 协议文档：docs/provider-api-documentation.md Section 2
 *
 * 特点：
 * - 异步返回（提交任务 → 轮询状态）
 * - 支持图生视频（image_url）
 * - 支持时长：3-10秒
 * - 支持比例：16:9, 9:16, 1:1
 * - 支持回调（callback_url）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToAPIsVideoAdapter implements ProviderAdapter {

    private static final String BASE_URL = "https://api.toapis.com";
    private static final String SUBMIT_ENDPOINT = "/v1/videos/seedance";
    private static final String STATUS_ENDPOINT = "/v1/videos/seedance/%s";
    private static final String CANCEL_ENDPOINT = "/v1/videos/seedance/%s/cancel";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public ProviderTaskResponse submitTask(ProviderTaskRequest request, String apiKey) {
        log.info("ToAPIs提交视频生成任务: prompt={}, imageUrl={}, duration={}",
            request.getPrompt(), request.getImageUrl(), request.getDuration());

        try {
            // 构建请求体
            ToAPIsVideoRequest videoRequest = new ToAPIsVideoRequest(
                request.getPrompt(),
                request.getImageUrl(),
                request.getDuration() != null ? request.getDuration() : 5,
                request.getAspectRatio() != null ? request.getAspectRatio() : "9:16",
                "seedance-2",
                null  // callback_url 暂不使用
            );

            String requestBody = objectMapper.writeValueAsString(videoRequest);

            // 发送HTTP请求
            Request httpRequest = new Request.Builder()
                .url(BASE_URL + SUBMIT_ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    return handleError(response.code(), responseBody);
                }

                // 解析成功响应
                JsonNode json = objectMapper.readTree(responseBody);

                return ProviderTaskResponse.builder()
                    .providerJobId(json.get("id").asText())
                    .status(mapStatus(json.get("status").asText()))
                    .progress(0)
                    .estimatedDuration(json.has("estimated_duration") ? json.get("estimated_duration").asInt() : null)
                    .createdAt(parseDateTime(json.get("created_at").asText()))
                    .build();
            }

        } catch (IOException e) {
            log.error("ToAPIs请求失败", e);
            return ProviderTaskResponse.builder()
                .status("FAILED")
                .errorCode("network_error")
                .errorMessage(e.getMessage())
                .build();
        }
    }

    @Override
    public ProviderTaskResponse checkTaskStatus(String providerJobId, String apiKey) {
        log.debug("ToAPIs查询任务状态: jobId={}", providerJobId);

        try {
            Request httpRequest = new Request.Builder()
                .url(BASE_URL + String.format(STATUS_ENDPOINT, providerJobId))
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    return handleError(response.code(), responseBody);
                }

                // 解析响应
                JsonNode json = objectMapper.readTree(responseBody);
                String status = json.get("status").asText();

                ProviderTaskResponse.ProviderTaskResponseBuilder builder = ProviderTaskResponse.builder()
                    .providerJobId(json.get("id").asText())
                    .status(mapStatus(status))
                    .progress(json.has("progress") ? json.get("progress").asInt() : null)
                    .createdAt(parseDateTime(json.get("created_at").asText()));

                // 完成状态
                if ("completed".equals(status)) {
                    builder
                        .resultUrl(json.get("video_url").asText())
                        .thumbnailUrl(json.has("thumbnail_url") ? json.get("thumbnail_url").asText() : null)
                        .durationSeconds(json.has("duration") ? BigDecimal.valueOf(json.get("duration").asDouble()) : null)
                        .width(json.has("width") ? json.get("width").asInt() : null)
                        .height(json.has("height") ? json.get("height").asInt() : null)
                        .fileSizeBytes(json.has("file_size") ? json.get("file_size").asLong() : null)
                        .completedAt(parseDateTime(json.get("completed_at").asText()));
                }

                // 失败状态
                if ("failed".equals(status) && json.has("error")) {
                    JsonNode error = json.get("error");
                    builder
                        .errorCode(error.get("code").asText())
                        .errorMessage(error.get("message").asText());
                }

                return builder.build();
            }

        } catch (IOException e) {
            log.error("ToAPIs查询状态失败", e);
            return ProviderTaskResponse.builder()
                .providerJobId(providerJobId)
                .status("FAILED")
                .errorCode("network_error")
                .errorMessage(e.getMessage())
                .build();
        }
    }

    @Override
    public boolean cancelTask(String providerJobId, String apiKey) {
        log.info("ToAPIs取消任务: jobId={}", providerJobId);

        try {
            Request httpRequest = new Request.Builder()
                .url(BASE_URL + String.format(CANCEL_ENDPOINT, providerJobId))
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                return response.isSuccessful();
            }

        } catch (IOException e) {
            log.error("ToAPIs取消任务失败", e);
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "TOAPIS_VIDEO";
    }

    @Override
    public boolean isSynchronous() {
        return false;
    }

    /**
     * 映射 ToAPIs 状态到统一状态
     */
    private String mapStatus(String toApisStatus) {
        return switch (toApisStatus) {
            case "pending" -> "PENDING";
            case "processing" -> "PROCESSING";
            case "completed" -> "COMPLETED";
            case "failed", "cancelled" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    /**
     * 解析ISO 8601时间
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            log.warn("解析时间失败: {}", dateTimeStr);
            return LocalDateTime.now();
        }
    }

    private ProviderTaskResponse handleError(int httpCode, String responseBody) {
        log.error("ToAPIs错误: code={}, body={}", httpCode, responseBody);

        try {
            JsonNode json = objectMapper.readTree(responseBody);

            return ProviderTaskResponse.builder()
                .status("FAILED")
                .errorCode(json.has("error") ? json.get("error").asText() : "http_" + httpCode)
                .errorMessage(json.has("message") ? json.get("message").asText() : responseBody)
                .build();

        } catch (Exception e) {
            return ProviderTaskResponse.builder()
                .status("FAILED")
                .errorCode("http_" + httpCode)
                .errorMessage(responseBody)
                .build();
        }
    }

    // 内部请求类
    private record ToAPIsVideoRequest(
        String prompt,
        String image_url,
        int duration,
        String aspect_ratio,
        String model,
        String callback_url
    ) {}
}
