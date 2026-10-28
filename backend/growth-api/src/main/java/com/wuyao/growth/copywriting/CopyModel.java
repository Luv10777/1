package com.wuyao.growth.copywriting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文案创作经网关调用平台的文本模型（TEXT_WRITER）。模型能看图时图片随请求一起发；
 * 回答要求是一个 JSON 对象，这里负责剥掉代码块和思考过程再解析。
 */
@Component
@RequiredArgsConstructor
class CopyModel {
    private final AiGateway gateway;
    private final ObjectMapper json;

    Map<String, Object> ask(Long tenantId, String system, String prompt, List<String> images, int maxTokens, String key) {
        var options = new LinkedHashMap<String, Object>();
        options.put("system", system);
        options.put("jsonObject", true);
        options.put("maxTokens", maxTokens);
        if (!images.isEmpty()) options.put("images", images);
        var response = gateway.invokeReal(new ProviderRequest(ModelAlias.TEXT_WRITER, tenantId, prompt, options, key));
        if (!response.succeeded() || response.output() == null || !(response.output().get("text") instanceof String text)) {
            throw new IllegalStateException("文本模型未返回有效结果");
        }
        return parse(json, text);
    }

    static Map<String, Object> parse(ObjectMapper json, String text) {
        String body = text.strip()
                .replaceFirst("(?is)^<think>.*?</think>\\s*", "")
                .replaceFirst("(?is)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
        try {
            return json.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            throw CopyPrompt.invalid("模型没有按格式返回文案，请重试");
        }
    }
}
