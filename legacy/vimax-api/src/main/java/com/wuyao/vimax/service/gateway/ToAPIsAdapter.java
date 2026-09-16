package com.wuyao.vimax.service.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.vimax.entity.ProviderJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * ToAPIs (Seedance) 适配器 - 真实实现
 *
 * 封装 ToAPIs Seedance 2.0/2.5 的视频生成接口
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToAPIsAdapter {

    private final ObjectMapper objectMapper;

    private static final String TOAPIS_BASE_URL = "https://api.toapis.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build();

    /**
     * 提交视频生成任务 (Seedance 2.0)
     */
    public String submitVideoGeneration(String imageUrl, String prompt,
                                       String modelCapability, String apiKey) {
        log.info("ToAPIs 提交视频生成：capability={}", modelCapability);

        try {
            // 构造请求体
            String requestBody = String.format(
                    "{\"image_url\":\"%s\",\"prompt_text\":\"%s\",\"model_version\":\"2.0\",\"duration\":5}",
                    imageUrl,
                    escapeJson(prompt)
            );

            Request request = new Request.Builder()
                    .url(TOAPIS_BASE_URL + "/v2/video/generation/create")
                    .post(RequestBody.create(requestBody, JSON))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    throw new IOException("ToAPIs 请求失败 [" + response.code() + "]: " + errorBody);
                }

                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);

                // 解析响应
                if (jsonNode.has("code") && jsonNode.get("code").asInt() != 0) {
                    String errorMsg = jsonNode.has("message") ? jsonNode.get("message").asText() : "Unknown error";
                    throw new RuntimeException("ToAPIs 返回错误: " + errorMsg);
                }

                String taskId = jsonNode.get("data").get("task_id").asText();
                log.info("ToAPIs 视频任务已提交：taskId={}", taskId);

                return taskId;
            }

        } catch (Exception e) {
            log.error("ToAPIs 视频生成失败", e);
            throw new RuntimeException("ToAPIs 视频生成失败: " + e.getMessage());
        }
    }

    /**
     * 检查任务状态
     */
    public void checkJobStatus(ProviderJob job, String apiKey) {
        log.debug("ToAPIs 检查任务状态：providerJobId={}", job.getProviderJobId());

        try {
            Request request = new Request.Builder()
                    .url(TOAPIS_BASE_URL + "/v2/video/generation/query?task_id=" + job.getProviderJobId())
                    .get()
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("ToAPIs 查询失败: {}", response.code());
                    return;
                }

                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);

                if (jsonNode.has("code") && jsonNode.get("code").asInt() != 0) {
                    log.warn("ToAPIs 返回错误: {}", jsonNode.get("message").asText());
                    return;
                }

                JsonNode data = jsonNode.get("data");
                String status = data.get("status").asText();

                // 映射状态
                switch (status) {
                    case "pending":
                        job.setStatus("SUBMITTED");
                        job.setProgress(0);
                        break;

                    case "queued":
                        job.setStatus("QUEUED");
                        job.setProgress(10);
                        break;

                    case "processing":
                        job.setStatus("PROCESSING");
                        // ToAPIs 不提供精确进度，估算
                        int currentProgress = job.getProgress() != null ? job.getProgress() : 20;
                        job.setProgress(Math.min(currentProgress + 10, 90));
                        break;

                    case "succeeded":
                        job.setStatus("COMPLETED");
                        job.setProgress(100);

                        // 获取视频 URL
                        if (data.has("video_url")) {
                            String videoUrl = data.get("video_url").asText();
                            job.setResultUrl(videoUrl);
                        }

                        // 获取视频元信息
                        if (data.has("duration")) {
                            job.setActualDurationSeconds(data.get("duration").decimalValue());
                        }
                        if (data.has("width")) {
                            job.setActualWidth(data.get("width").asInt());
                        }
                        if (data.has("height")) {
                            job.setActualHeight(data.get("height").asInt());
                        }
                        break;

                    case "failed":
                        job.setStatus("FAILED");
                        String errorMsg = data.has("error_message") ?
                                data.get("error_message").asText() : "Generation failed";
                        job.setErrorMessage(errorMsg);

                        if (data.has("error_code")) {
                            job.setErrorCode(data.get("error_code").asText());
                        }
                        break;

                    default:
                        log.warn("未知的 ToAPIs 状态: {}", status);
                }

            }

        } catch (Exception e) {
            log.error("ToAPIs 状态查询失败", e);
        }
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
