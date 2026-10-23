package com.wuyao.growth.common.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.video.plan.VideoPlanProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.util.*;

/** OpenAI 兼容的看图对话接口：系统提示 + 文本 + 若干 JPEG 图片，要求返回 JSON 对象。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoPlanHttpAdapter implements ProviderAdapter {
    private final VideoPlanProperties config;
    private final ObjectMapper json;
    private final HttpClient httpClient;

    @Override public String code() { return "VIDEO_PLAN_HTTP"; }
    @Override public Set<ModelAlias> supports() {
        return config.configured() ? EnumSet.of(ModelAlias.VIDEO_PLANNER) : EnumSet.noneOf(ModelAlias.class);
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        try {
            var content = new ArrayList<Map<String, Object>>();
            content.add(Map.of("type", "text", "text", request.prompt()));
            Object images = request.options().getOrDefault("images", List.of());
            if (!(images instanceof List<?> list) || list.size() > 6) throw failure("参考图数量不合法");
            for (Object value : list) {
                if (!(value instanceof String url) || !url.startsWith("data:image/jpeg;base64,")) throw failure("参考图格式不合法");
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
            }
            var body = new LinkedHashMap<String, Object>();
            body.put("model", config.getModel());
            body.put("stream", false);
            body.put("max_tokens", config.getMaxTokens());
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(Map.of("role", "system", "content", request.options().get("system")),
                    Map.of("role", "user", "content", content)));
            var post = new HttpPost(config.getBaseUrl().replaceFirst("/+$", "") + "/chat/completions");
            post.setHeader("Authorization", "Bearer " + config.getApiKey());
            post.setHeader("Idempotency-Key", request.idempotencyKey());
            post.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(10))
                    .setResponseTimeout(Timeout.ofSeconds(config.getTimeoutSeconds())).build());
            post.setEntity(new ByteArrayEntity(json.writeValueAsBytes(body), ContentType.APPLICATION_JSON));
            byte[] bytes = httpClient.execute(post, response -> {
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    log.warn("视频方案模型调用失败: httpStatus={}", response.getCode());
                    throw failure("出方案模型请求失败（HTTP " + response.getCode() + "），请稍后重试");
                }
                if (response.getEntity() == null) throw failure("出方案模型返回空响应");
                try (var input = response.getEntity().getContent()) {
                    byte[] result = input.readNBytes(1024 * 1024 + 1);
                    if (result.length > 1024 * 1024) throw failure("出方案模型响应过大");
                    return result;
                }
            });
            var root = json.readTree(bytes);
            var choice = root.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) throw failure("模型没有完整返回方案，请重试");
            // 这个模型即使要求 JSON 也常包一层 ```json 代码块。
            String text = choice.path("message").path("content").asText().strip()
                    .replaceFirst("(?is)^<think>.*?</think>\\s*", "")
                    .replaceFirst("(?is)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
            Map<String, Object> output = json.readValue(text, new TypeReference<>() {});
            output.put("_model", config.getModel());
            return new ProviderResult(true, code(), null, output, null, null);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 上游响应正文和密钥都不进日志或任务错误。
            log.warn("视频方案模型响应失败: exception={}", e.getClass().getSimpleName());
            throw failure("出方案模型响应异常或超时，请稍后重试");
        }
    }

    private BizException failure(String message) { return BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, message); }
}
