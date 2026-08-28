package com.wuyao.vimax.service.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * FluAPI 图片生成 Adapter
 *
 * 协议文档：docs/provider-api-documentation.md Section 1
 *
 * 特点：
 * - 同步返回
 * - 支持多尺寸：1024x1024, 1024x1792, 1792x1024
 * - 支持质量：standard, hd
 * - 支持风格：vivid, natural
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FluAPIImageAdapter implements ProviderAdapter {

    private static final String BASE_URL = "https://api.fluapi.com";
    private static final String ENDPOINT = "/v1/images/generations";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public ProviderTaskResponse submitTask(ProviderTaskRequest request, String apiKey) {
        log.info("FluAPI提交图片生成任务: prompt={}, size={}", request.getPrompt(), request.getSize());

        try {
            // 构建请求体
            String requestBody = objectMapper.writeValueAsString(new FluAPIImageRequest(
                "gpt-image-2",
                request.getPrompt(),
                1,
                request.getSize() != null ? request.getSize() : "1024x1024",
                request.getQuality() != null ? request.getQuality() : "standard",
                request.getStyle() != null ? request.getStyle() : "vivid",
                "url"
            ));

            // 发送HTTP请求
            Request httpRequest = new Request.Builder()
                .url(BASE_URL + ENDPOINT)
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
                JsonNode dataNode = json.get("data").get(0);

                return ProviderTaskResponse.builder()
                    .providerJobId("fluapi_sync_" + System.currentTimeMillis())
                    .status("COMPLETED")
                    .progress(100)
                    .resultUrl(dataNode.get("url").asText())
                    .createdAt(LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(json.get("created").asLong()),
                        ZoneId.systemDefault()))
                    .completedAt(LocalDateTime.now())
                    .build();
            }

        } catch (IOException e) {
            log.error("FluAPI请求失败", e);
            return ProviderTaskResponse.builder()
                .status("FAILED")
                .errorCode("network_error")
                .errorMessage(e.getMessage())
                .build();
        }
    }

    @Override
    public ProviderTaskResponse checkTaskStatus(String providerJobId, String apiKey) {
        // FluAPI是同步的，不需要查询状态
        throw new UnsupportedOperationException("FluAPI is synchronous, no status check needed");
    }

    @Override
    public String getProviderName() {
        return "FLUAPI_IMAGE";
    }

    @Override
    public boolean isSynchronous() {
        return true;
    }

    private ProviderTaskResponse handleError(int httpCode, String responseBody) {
        log.error("FluAPI错误: code={}, body={}", httpCode, responseBody);

        try {
            JsonNode json = objectMapper.readTree(responseBody);
            JsonNode error = json.get("error");

            return ProviderTaskResponse.builder()
                .status("FAILED")
                .errorCode(error.get("code").asText())
                .errorMessage(error.get("message").asText())
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
    private record FluAPIImageRequest(
        String model,
        String prompt,
        int n,
        String size,
        String quality,
        String style,
        String response_format
    ) {}
}
