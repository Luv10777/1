package com.wuyao.growth.common.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.video.analysis.VideoAnalysisProperties;
import com.wuyao.growth.video.analysis.VideoAnalysisAudioProperties;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class VideoAnalysisHttpAdapter implements ProviderAdapter {
    private final VideoAnalysisProperties config;
    private final VideoAnalysisAudioProperties audioConfig;
    private final ObjectMapper json;
    private final HttpClient httpClient;

    @Override public String code() { return "VIDEO_ANALYSIS_HTTP"; }
    @Override public Set<ModelAlias> supports() {
        var supported = EnumSet.noneOf(ModelAlias.class);
        if (config.configured()) supported.add(ModelAlias.VISION_ANALYZER);
        if (audioConfig.configured()) supported.add(ModelAlias.AUDIO_ANALYZER);
        return supported;
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        try {
            var content = new ArrayList<Map<String, Object>>();
            content.add(Map.of("type", "text", "text", request.prompt()));
            boolean audio = request.alias() == ModelAlias.AUDIO_ANALYZER;
            if (audio) {
                Object value = request.options().get("audioDataUrl");
                String prefix = "data:audio/wav;base64,";
                if (!(value instanceof String url) || !url.startsWith(prefix) || url.length() <= prefix.length()) {
                    throw failure("分析音频格式不合法");
                }
                String data = audioConfig.getInputEncoding() == VideoAnalysisAudioProperties.InputEncoding.BASE64
                        ? url.substring(prefix.length()) : url;
                content.add(Map.of("type", "input_audio", "input_audio", Map.of("data", data, "format", "wav")));
            } else {
            Object references = request.options().get("frames");
            if (!(references instanceof List<?> frames) || frames.isEmpty() || frames.size() > config.getMaxFrames()) {
                throw failure("分析图片数量不合法");
            }
            for (Object value : frames) {
                if (!(value instanceof Map<?, ?> frame) || !(frame.get("dataUrl") instanceof String url)
                        || !url.startsWith("data:image/jpeg;base64,")) throw failure("分析图片格式不合法");
                content.add(Map.of("type", "text", "text", "Approximate timestamp: " + frame.get("seconds") + " seconds"));
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", url, "detail", "auto")));
            }
            }
            String model = audio ? audioConfig.getModel() : config.getModel();
            var body = new LinkedHashMap<String, Object>();
            body.putAll(Map.of("model", model, "stream", false, "reasoning_effort", "low",
                    audio ? "max_tokens" : "max_completion_tokens", audio ? audioConfig.getMaxTokens() : config.getMaxTokens(),
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(Map.of("role", "system", "content", request.options().get("system")),
                            Map.of("role", "user", "content", content))));
            if (audio) body.put("modalities", List.of("text"));
            String base = (audio ? audioConfig.getBaseUrl() : config.getBaseUrl()).replaceFirst("/+$", "");
            var post = new HttpPost(base + "/chat/completions");
            post.setHeader("Authorization", "Bearer " + (audio ? audioConfig.getApiKey() : config.getApiKey()));
            post.setHeader("Idempotency-Key", request.idempotencyKey());
            post.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(10))
                    .setResponseTimeout(Timeout.ofSeconds(audio ? audioConfig.getTimeoutSeconds() : config.getTimeoutSeconds())).build());
            post.setEntity(new ByteArrayEntity(json.writeValueAsBytes(body), ContentType.APPLICATION_JSON));
            byte[] bytes = httpClient.execute(post, response -> {
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    log.warn("视频分析模型调用失败: alias={} httpStatus={}", request.alias(), response.getCode());
                    throw failure("视频分析模型请求失败（HTTP " + response.getCode() + "），请稍后重试");
                }
                if (response.getEntity() == null) throw failure("视频分析模型返回空响应");
                try (var input = response.getEntity().getContent()) {
                    byte[] result = input.readNBytes(1024 * 1024 + 1);
                    if (result.length > 1024 * 1024) throw failure("视频分析模型响应过大");
                    return result;
                }
            });
            var root = json.readTree(bytes);
            var choice = root.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) throw failure("模型未完整返回分析结果，请重新分析");
            String text = choice.path("message").path("content").asText().strip()
                    .replaceFirst("(?is)^<think>.*?</think>\\s*", "")
                    .replaceFirst("(?is)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
            Map<String, Object> output = json.readValue(text, new TypeReference<>() {});
            output.put("_model", model);
            if (root.path("usage").isObject()) output.put("_usage", json.convertValue(root.get("usage"), Map.class));
            return new ProviderResult(true, code(), null, output, null, null);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // Upstream bodies and credentials never enter task errors or logs.
            log.warn("视频分析模型响应失败: alias={} exception={}", request.alias(), e.getClass().getSimpleName());
            throw failure("视频分析模型响应异常或超时，请稍后重试");
        }
    }

    private BizException failure(String message) { return BizException.of(ErrorCode.VIDEO_PROVIDER_ERROR, message); }
}
