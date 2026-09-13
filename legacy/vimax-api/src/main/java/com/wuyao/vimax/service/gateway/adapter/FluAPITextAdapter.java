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
import java.util.List;

/**
 * FluAPI 文本生成 Adapter (gpt5.6-luna)
 *
 * 协议：OpenAI-compatible API
 * Base URL: https://api.fluapi.com/v1
 * Model: gpt5.6-luna
 *
 * 特点：
 * - 同步返回
 * - OpenAI Chat Completions 兼容接口
 * - 支持流式和非流式
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FluAPITextAdapter implements ProviderAdapter {

    private static final String BASE_URL = "https://api.fluapi.com";
    private static final String ENDPOINT = "/v1/chat/completions";
    private static final String MODEL = "gpt5.6-luna";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public ProviderTaskResponse submitTask(ProviderTaskRequest request, String apiKey) {
        log.info("FluAPI提交文本生成任务: prompt={}", request.getPrompt().substring(0, Math.min(50, request.getPrompt().length())));

        try {
            // 构建请求体（OpenAI格式）
            FluAPITextRequest textRequest = new FluAPITextRequest(
                MODEL,
                List.of(new Message("user", request.getPrompt())),
                0.7,      // temperature
                null,     // max_tokens (使用默认值)
                false     // stream
            );

            String requestBody = objectMapper.writeValueAsString(textRequest);

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
                JsonNode choice = json.get("choices").get(0);
                String content = choice.get("message").get("content").asText();

                return ProviderTaskResponse.builder()
                    .providerJobId("fluapi_text_" + json.get("id").asText())
                    .status("COMPLETED")
                    .progress(100)
                    .resultUrl(content)  // 文本内容存储在 resultUrl 字段
                    .createdAt(LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(json.get("created").asLong()),
                        ZoneId.systemDefault()))
                    .completedAt(LocalDateTime.now())
                    .build();
            }

        } catch (IOException e) {
            log.error("FluAPI文本请求失败", e);
            return ProviderTaskResponse.builder()
                .status("FAILED")
                .errorCode("network_error")
                .errorMessage(e.getMessage())
                .build();
        }
    }

    @Override
    public ProviderTaskResponse checkTaskStatus(String providerJobId, String apiKey) {
        // FluAPI文本生成是同步的，不需要查询状态
        throw new UnsupportedOperationException("FluAPI text generation is synchronous");
    }

    @Override
    public String getProviderName() {
        return "FLUAPI_TEXT";
    }

    @Override
    public boolean isSynchronous() {
        return true;
    }

    private ProviderTaskResponse handleError(int httpCode, String responseBody) {
        log.error("FluAPI文本错误: code={}, body={}", httpCode, responseBody);

        try {
            JsonNode json = objectMapper.readTree(responseBody);
            JsonNode error = json.get("error");

            return ProviderTaskResponse.builder()
                .status("FAILED")
                .errorCode(error.has("code") ? error.get("code").asText() : "http_" + httpCode)
                .errorMessage(error.has("message") ? error.get("message").asText() : responseBody)
                .build();

        } catch (Exception e) {
            return ProviderTaskResponse.builder()
                .status("FAILED")
                .errorCode("http_" + httpCode)
                .errorMessage(responseBody)
                .build();
        }
    }

    // 内部请求类（OpenAI格式）
    private record FluAPITextRequest(
        String model,
        List<Message> messages,
        double temperature,
        Integer max_tokens,
        boolean stream
    ) {}

    private record Message(
        String role,
        String content
    ) {}
}
