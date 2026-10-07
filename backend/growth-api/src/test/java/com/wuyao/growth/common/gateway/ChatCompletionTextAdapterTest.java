package com.wuyao.growth.common.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatCompletionTextAdapterTest {
    private static final String URL = "https://models.test/v1/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = mock(HttpClient.class);

    @Test
    void registersTheWriterCapabilityOnlyWhenFullyConfiguredOverASafeTransport() {
        assertThat(adapter(URL, "key", "model").supports()).containsExactly(ModelAlias.TEXT_WRITER);
        assertThat(adapter("http://localhost:11434/v1/chat/completions", "key", "model").supports())
                .containsExactly(ModelAlias.TEXT_WRITER);
        assertThat(adapter("", "key", "model").supports()).isEmpty();
        assertThat(adapter(URL, " ", "model").supports()).isEmpty();
        assertThat(adapter(URL, "key", "").supports()).isEmpty();
        // A key must not be sent in clear text to a remote host, nor to a URL carrying credentials.
        assertThat(adapter("http://models.test/v1/chat/completions", "key", "model").supports()).isEmpty();
        assertThat(adapter("https://user:pass@models.test/v1", "key", "model").supports()).isEmpty();
        assertThat(adapter("not a url", "key", "model").supports()).isEmpty();
    }

    @Test
    void sendsSystemAndUserMessagesAndReturnsTheTextWithUsageButNoInventedCost() throws Exception {
        respond(200, """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"  家人们看过来  "}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}""");

        ProviderResult result = adapter(URL, "secret-key", "writer-1").invoke(new ProviderRequest(ModelAlias.TEXT_WRITER,
                1L, "商品资料……", Map.of("system", "你是主播", "temperature", 0.3, "maxTokens", 200), "live-script-9-1"));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.providerCode()).isEqualTo("TEXT_HTTP");
        assertThat(result.output()).containsEntry("text", "家人们看过来").containsEntry("_model", "writer-1");
        assertThat(result.output().get("_usage")).isEqualTo(Map.of("prompt_tokens", 120, "completion_tokens", 30, "total_tokens", 150));
        assertThat(result.cost()).isNull();

        HttpRequest sent = sent();
        assertThat(sent.uri().toString()).isEqualTo(URL);
        assertThat(sent.headers().firstValue("Authorization")).contains("Bearer secret-key");
        JsonNode body = json.readTree(body(sent));
        assertThat(body.path("model").asText()).isEqualTo("writer-1");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.3);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(200);
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("你是主播");
        assertThat(body.at("/messages/1/content").asText()).isEqualTo("商品资料……");
    }

    @Test
    void providerSpecificSwitchesAreSentAsConfiguredButCannotReplaceTheRequestItself() throws Exception {
        respond(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"家人们看过来\"}}]}");

        adapter(URL, "key", "writer-1", "{\"enable_thinking\":false,\"model\":\"other\",\"stream\":true,\"messages\":[]}")
                .invoke(new ProviderRequest(ModelAlias.TEXT_WRITER, 1L, "商品资料", Map.of(), "key"));

        JsonNode body = json.readTree(body(sent()));
        // Without this switch a reasoning model spends most of its time and tokens thinking.
        assertThat(body.path("enable_thinking").isBoolean()).isTrue();
        assertThat(body.path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.path("model").asText()).isEqualTo("writer-1");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("messages")).hasSize(1);
    }

    @Test
    void aCallThatAsksForReasoningGetsTheSwitchesConfiguredForItOverTheEverydayOnes() throws Exception {
        respond(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}");

        adapter(URL, "key", "writer-1", "{\"enable_thinking\":false}")
                .invoke(new ProviderRequest(ModelAlias.TEXT_WRITER, 1L, "一条弹幕", Map.of("reasoning", true), "key"));

        JsonNode body = json.readTree(body(sent()));
        // Judging whether and how to answer a viewer is worth the seconds; narration never asks for this.
        assertThat(body.path("enable_thinking").asBoolean()).isTrue();
        // Like the everyday switches, they cannot replace the request itself.
        assertThat(body.path("model").asText()).isEqualTo("writer-1");
    }

    @Test
    void aMalformedSwitchIsRejectedAtStartupInsteadOfBeingSilentlyIgnored() {
        assertThatThrownBy(() -> adapter(URL, "key", "writer-1", "enable_thinking=false"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TEXT_WRITER_EXTRA_BODY");
        assertThatThrownBy(() -> adapter(URL, "key", "writer-1", "[1,2]"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(adapter(URL, "key", "writer-1", "  ").supports()).containsExactly(ModelAlias.TEXT_WRITER);
    }

    @Test
    void failuresAreReportedInOurWordsAndNeverEchoTheProviderBodyOrTheKey() throws Exception {
        respond(401, "{\"error\":{\"message\":\"Incorrect API key provided: secret-key\"}}");
        assertThatThrownBy(() -> invoke()).isInstanceOf(BizException.class)
                .hasMessageContaining("令牌无效").hasMessageNotContaining("secret-key").hasMessageNotContaining("Incorrect");

        respond(429, "{}");
        assertThatThrownBy(() -> invoke()).hasMessageContaining("额度不足");

        respond(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"说到一半\"}}]}");
        assertThatThrownBy(() -> invoke()).hasMessageContaining("超出长度限制");

        respond(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"  \"}}]}");
        assertThatThrownBy(() -> invoke()).hasMessageContaining("没有返回内容");

        when(http.send(any(HttpRequest.class), any())).thenThrow(new IOException("connect timed out to 10.0.0.8"));
        assertThatThrownBy(() -> invoke()).hasMessageContaining("响应异常或超时").hasMessageNotContaining("10.0.0.8");
    }

    private ProviderResult invoke() {
        return adapter(URL, "secret-key", "writer-1")
                .invoke(new ProviderRequest(ModelAlias.TEXT_WRITER, 1L, "商品资料", Map.of(), "key"));
    }

    private ChatCompletionTextAdapter adapter(String url, String key, String model) {
        return adapter(url, key, model, "");
    }

    private ChatCompletionTextAdapter adapter(String url, String key, String model, String extraBody) {
        return new ChatCompletionTextAdapter(json, url, key, model, 0.8, 800, 60, extraBody,
                "{\"enable_thinking\":true,\"model\":\"must-not-win\"}", http);
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        reset(http);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private HttpRequest sent() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        return captor.getValue();
    }

    private static String body(HttpRequest request) {
        List<ByteBuffer> buffers = new ArrayList<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer item) { buffers.add(item); }
            public void onError(Throwable throwable) { }
            public void onComplete() { }
        });
        StringBuilder text = new StringBuilder();
        for (ByteBuffer buffer : buffers) text.append(StandardCharsets.UTF_8.decode(buffer));
        return text.toString();
    }
}
