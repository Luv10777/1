package com.wuyao.growth.common.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文案能力（TEXT_WRITER）的真实适配器：任何 OpenAI 兼容的 chat completions 接口。
 *
 * 没配置地址、密钥或模型时不登记任何能力，网关会落到占位适配器上；
 * 调用方靠输出里有没有 text 区分真假结果，不会把占位回显当成文案。
 *
 * 排在占位适配器之前，配置好之后网关自然选它。
 */
@Slf4j
@Component
@Order(0)
public class ChatCompletionTextAdapter implements ProviderAdapter {
    /** 这些字段由适配器自己决定，附加参数不能改写。 */
    private static final Set<String> RESERVED = Set.of("model", "messages", "stream");
    private final ObjectMapper json;
    private final HttpClient http;
    private final String url;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final Map<String, Object> extraBody;
    /** 调用方要求“想清楚再答”时并入请求体的开关，盖过上面的默认值。 */
    private final Map<String, Object> reasoningBody;

    @Autowired
    public ChatCompletionTextAdapter(ObjectMapper json,
                                     @Value("${growth.ai.writer.url:}") String url,
                                     @Value("${growth.ai.writer.api-key:}") String apiKey,
                                     @Value("${growth.ai.writer.model:}") String model,
                                     @Value("${growth.ai.writer.temperature:0.8}") double temperature,
                                     @Value("${growth.ai.writer.max-tokens:800}") int maxTokens,
                                     @Value("${growth.ai.writer.timeout-seconds:60}") int timeoutSeconds,
                                     @Value("${growth.ai.writer.extra-body:}") String extraBody,
                                     @Value("${growth.ai.writer.reasoning-extra-body:}") String reasoningBody) {
        this(json, url, apiKey, model, temperature, maxTokens, timeoutSeconds, extraBody, reasoningBody,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    ChatCompletionTextAdapter(ObjectMapper json, String url, String apiKey, String model, double temperature,
                              int maxTokens, int timeoutSeconds, String extraBody, String reasoningBody, HttpClient http) {
        this.json = json;
        this.url = url == null ? "" : url.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.extraBody = parseExtraBody(json, extraBody, "TEXT_WRITER_EXTRA_BODY");
        this.reasoningBody = parseExtraBody(json, reasoningBody, "TEXT_WRITER_REASONING_EXTRA_BODY");
        this.http = http;
    }

    /**
     * 供应商各自的开关（例如关闭思考模式）没有统一写法，只能原样带上。
     * 写错了就在启动时报出来：悄悄忽略的话，表现只是“莫名很慢”或“莫名很贵”，很难查。
     */
    private static Map<String, Object> parseExtraBody(ObjectMapper json, String value, String name) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
            parsed.keySet().removeAll(RESERVED);
            return Map.copyOf(parsed);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(name + " 必须是一个 JSON 对象，例如 {\"enable_thinking\":false}");
        }
    }

    @Override
    public String code() {
        return "TEXT_HTTP";
    }

    @Override
    public Set<ModelAlias> supports() {
        return configured() ? Set.of(ModelAlias.TEXT_WRITER) : Set.of();
    }

    /** 密钥只允许走 HTTPS；本机地址例外，方便联调自建或模拟的模型服务。 */
    boolean configured() {
        if (apiKey.isEmpty() || model.isEmpty() || url.isEmpty()) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) return false;
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            return "https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        Map<String, Object> options = request.options() == null ? Map.of() : request.options();
        List<Map<String, Object>> messages = new ArrayList<>();
        if (options.get("system") instanceof String system && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        messages.add(Map.of("role", "user", "content", request.prompt() == null ? "" : request.prompt()));
        Map<String, Object> body = new LinkedHashMap<>(extraBody);
        // 判断类的调用宁可慢几秒也要想清楚；写口播的调用不带这个选项，照旧走快的路。
        boolean reasoning = Boolean.TRUE.equals(options.get("reasoning"));
        if (reasoning) body.putAll(reasoningBody);
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);
        body.put("temperature", options.get("temperature") instanceof Number value ? value : temperature);
        body.put("max_tokens", options.get("maxTokens") instanceof Number value ? value.intValue() : maxTokens);
        long started = System.nanoTime();
        try {
            HttpRequest http = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = this.http.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw failure(reason(response.statusCode()));
            JsonNode root = json.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            if ("length".equals(choice.path("finish_reason").asText())) throw failure("文本模型输出超出长度限制");
            String text = choice.path("message").path("content").asText("").strip();
            if (text.isEmpty()) throw failure("文本模型没有返回内容");
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("text", text);
            output.put("_model", model);
            if (root.path("usage").isObject()) output.put("_usage", json.convertValue(root.get("usage"), Map.class));
            // 耗时和思考用量是定位“慢”和“贵”的第一手数据；不记录提示词和正文。
            log.info("文本模型调用完成: model={} reasoning={} elapsedMs={} completionTokens={} reasoningTokens={}", model, reasoning,
                    (System.nanoTime() - started) / 1_000_000, root.at("/usage/completion_tokens").asInt(-1),
                    root.at("/usage/completion_tokens_details/reasoning_tokens").asInt(0));
            // 供应商没有报价时不能填 0：缺失的成本不等于免费。
            return new ProviderResult(true, code(), null, output, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("文本模型请求被中断");
        } catch (IOException e) {
            log.warn("文本模型调用失败: model={} elapsedMs={} type={}", model,
                    (System.nanoTime() - started) / 1_000_000, e.getClass().getSimpleName());
            // 响应体可能带有供应商的内部信息，不向外传递。
            throw failure("文本模型响应异常或超时，请稍后重试");
        }
    }

    private String reason(int status) {
        return switch (status) {
            case 401 -> "文本模型令牌无效或已过期";
            case 403 -> "当前令牌无权使用文本模型（" + model + "）";
            case 404 -> "文本模型接口或模型 ID 不存在（" + model + "）";
            case 429 -> "文本模型请求过于频繁或额度不足";
            default -> "文本模型请求失败（HTTP " + status + "）";
        };
    }

    private static BizException failure(String message) {
        return BizException.of(ErrorCode.INTERNAL_ERROR, message);
    }
}
