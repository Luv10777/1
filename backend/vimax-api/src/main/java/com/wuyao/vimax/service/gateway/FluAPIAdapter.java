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
 * FluAPI 适配器 - 真实实现
 *
 * 封装 FluAPI 的文本和图片生成接口
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FluAPIAdapter {

    private final ObjectMapper objectMapper;

    private static final String FLUAPI_BASE_URL = "https://api.fluapi.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * 提交文本生成任务
     */
    public String submitTextGeneration(String prompt, String modelCapability, String apiKey) {
        log.info("FluAPI 提交文本生成：capability={}", modelCapability);

        try {
            // 构造请求
            String requestBody = String.format(
                    "{\"model\":\"%s\",\"prompt\":\"%s\",\"max_tokens\":2000}",
                    getModelName(modelCapability),
                    escapeJson(prompt)
            );

            Request request = new Request.Builder()
                    .url(FLUAPI_BASE_URL + "/v1/text/generation")
                    .post(RequestBody.create(requestBody, JSON))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("FluAPI 请求失败: " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);

                String jobId = jsonNode.get("id").asText();
                log.info("FluAPI 文本任务已提交：jobId={}", jobId);

                return jobId;
            }

        } catch (Exception e) {
            log.error("FluAPI 文本生成失败", e);
            throw new RuntimeException("FluAPI 文本生成失败: " + e.getMessage());
        }
    }

    /**
     * 提交图片生成任务
     */
    public String submitImageGeneration(String prompt, String modelCapability, String apiKey) {
        log.info("FluAPI 提交图片生成：capability={}", modelCapability);

        try {
            // 构造请求
            String requestBody = String.format(
                    "{\"model\":\"%s\",\"prompt\":\"%s\",\"size\":\"1024x1024\",\"n\":1}",
                    "image-2.0",
                    escapeJson(prompt)
            );

            Request request = new Request.Builder()
                    .url(FLUAPI_BASE_URL + "/v1/image/generation")
                    .post(RequestBody.create(requestBody, JSON))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("FluAPI 请求失败: " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);

                String jobId = jsonNode.get("id").asText();
                log.info("FluAPI 图片任务已提交：jobId={}", jobId);

                return jobId;
            }

        } catch (Exception e) {
            log.error("FluAPI 图片生成失败", e);
            throw new RuntimeException("FluAPI 图片生成失败: " + e.getMessage());
        }
    }

    /**
     * 检查任务状态
     */
    public void checkJobStatus(ProviderJob job, String apiKey) {
        log.debug("FluAPI 检查任务状态：providerJobId={}", job.getProviderJobId());

        try {
            Request request = new Request.Builder()
                    .url(FLUAPI_BASE_URL + "/v1/job/" + job.getProviderJobId())
                    .get()
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("FluAPI 查询失败: {}", response.code());
                    return;
                }

                String responseBody = response.body().string();
                JsonNode jsonNode = objectMapper.readTree(responseBody);

                String status = jsonNode.get("status").asText();
                int progress = jsonNode.has("progress") ? jsonNode.get("progress").asInt() : 0;

                // 更新任务状态
                job.setProgress(progress);

                switch (status) {
                    case "completed":
                        job.setStatus("COMPLETED");
                        String resultUrl = jsonNode.get("result_url").asText();
                        job.setResultUrl(resultUrl);
                        break;
                    case "failed":
                        job.setStatus("FAILED");
                        String errorMsg = jsonNode.has("error") ? jsonNode.get("error").asText() : "Unknown error";
                        job.setErrorMessage(errorMsg);
                        break;
                    case "processing":
                        job.setStatus("PROCESSING");
                        break;
                    default:
                        job.setStatus("QUEUED");
                }

            }

        } catch (Exception e) {
            log.error("FluAPI 状态查询失败", e);
        }
    }

    /**
     * 根据能力获取模型名称
     */
    private String getModelName(String modelCapability) {
        if (modelCapability.contains("GPT4")) {
            return "gpt-4-turbo";
        } else if (modelCapability.contains("GPT3.5")) {
            return "gpt-3.5-turbo";
        }
        return "gpt-3.5-turbo";
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
