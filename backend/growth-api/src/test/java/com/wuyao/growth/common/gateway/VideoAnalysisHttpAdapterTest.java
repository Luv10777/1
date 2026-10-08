package com.wuyao.growth.common.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.video.analysis.VideoAnalysisProperties;
import com.wuyao.growth.video.analysis.VideoAnalysisAudioProperties;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class VideoAnalysisHttpAdapterTest {
    @ParameterizedTest
    @ValueSource(strings = {"gpt-6-luna", "gemini-3.8-flash"})
    void sendsOrderedImagesAndTimestampsToTheConfiguredModel(String model) throws Exception {
        var json = new ObjectMapper();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var pool = Executors.newSingleThreadExecutor(); var client = HttpClients.createDefault()) {
            String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", "{\"summary\":\"两张画面\"}"))), "usage", Map.of("total_tokens", 150)));
            var captured = pool.submit(() -> respond(server, 200, response));
            var config = config(server); config.setModel(model);
            var adapter = new VideoAnalysisHttpAdapter(config, new VideoAnalysisAudioProperties(), json, client);
            var result = adapter.invoke(request());
            assertThat(adapter.supports()).containsExactly(ModelAlias.VISION_ANALYZER);
            assertThat(result.output()).containsEntry("summary", "两张画面").containsEntry("_model", model);
            String raw = captured.get(5, TimeUnit.SECONDS);
            assertThat(raw).contains("Bearer unit-test-key", "Idempotency-Key: analysis-1");
            var body = json.readTree(raw.substring(raw.indexOf("\r\n\r\n") + 4));
            assertThat(body.get("model").asText()).isEqualTo(model);
            assertThat(body.path("messages").path(1).path("content")).hasSize(5);
            assertThat(body.path("messages").path(1).path("content").path(3).path("text").asText()).contains("1.5");
            assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        }
    }

    @Test void acceptsJsonWrappedInThinkingAndMarkdownByTheNewProvider() throws Exception {
        var json = new ObjectMapper();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var pool = Executors.newSingleThreadExecutor(); var client = HttpClients.createDefault()) {
            String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", "<think>provider analysis</think>\n\n```json\n{\"summary\":\"红色方块\"}\n```")))));
            var captured = pool.submit(() -> respond(server, 200, response));
            var result = new VideoAnalysisHttpAdapter(config(server), new VideoAnalysisAudioProperties(), json, client).invoke(request());
            assertThat(result.output()).containsEntry("summary", "红色方块").containsEntry("_model", "gpt-6-luna");
            captured.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void upstreamErrorBodiesAndCredentialsCannotReachTheUser() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var pool = Executors.newSingleThreadExecutor(); var client = HttpClients.createDefault()) {
            var captured = pool.submit(() -> respond(server, 401, "private-debug-data unit-test-key"));
            var adapter = new VideoAnalysisHttpAdapter(config(server), new VideoAnalysisAudioProperties(), new ObjectMapper(), client);
            assertThatThrownBy(() -> adapter.invoke(request())).isInstanceOf(BizException.class)
                    .hasMessageContaining("401").hasMessageNotContaining("private-debug-data").hasMessageNotContaining("unit-test-key");
            captured.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATA_URL", "BASE64"})
    void audioCallsUseTheirOwnEndpointCredentialAndEncoding(String encoding) throws Exception {
        var json = new ObjectMapper();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var pool = Executors.newSingleThreadExecutor(); var client = HttpClients.createDefault()) {
            var audio = new VideoAnalysisAudioProperties();
            audio.setBaseUrl("http://127.0.0.1:" + server.getLocalPort() + "/audio-v1");
            audio.setApiKey("audio-test-key"); audio.setModel("audio-test-model");
            audio.setInputEncoding(VideoAnalysisAudioProperties.InputEncoding.valueOf(encoding));
            var visual = new VideoAnalysisProperties(); visual.setApiKey("visual-test-key");
            visual.setBaseUrl("http://127.0.0.1:1/visual-v1");
            String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", "{\"summary\":\"音频内容\"}")))));
            var captured = pool.submit(() -> respond(server, 200, response));
            var adapter = new VideoAnalysisHttpAdapter(visual, audio, json, client);
            var result = adapter.invoke(new ProviderRequest(ModelAlias.AUDIO_ANALYZER, 1L, "分析声音",
                    Map.of("system", "rules", "audioDataUrl", "data:audio/wav;base64,QUJD"), "audio-1"));
            assertThat(adapter.supports()).containsExactlyInAnyOrder(ModelAlias.VISION_ANALYZER, ModelAlias.AUDIO_ANALYZER);
            String raw = captured.get(5, TimeUnit.SECONDS);
            assertThat(raw).contains("POST /audio-v1/chat/completions", "Bearer audio-test-key").doesNotContain("visual-test-key");
            var body = json.readTree(raw.substring(raw.indexOf("\r\n\r\n") + 4));
            assertThat(body.path("model").asText()).isEqualTo("audio-test-model");
            assertThat(body.path("messages").path(1).path("content").path(1).path("input_audio").path("data").asText())
                    .isEqualTo("BASE64".equals(encoding) ? "QUJD" : "data:audio/wav;base64,QUJD");
            assertThat(result.output()).containsEntry("_model", "audio-test-model");
        }
    }

    private VideoAnalysisProperties config(ServerSocket server) {
        var config = new VideoAnalysisProperties(); config.setApiKey("unit-test-key");
        config.setBaseUrl("http://127.0.0.1:" + server.getLocalPort() + "/v1/");
        return config;
    }
    private ProviderRequest request() {
        return new ProviderRequest(ModelAlias.VISION_ANALYZER, 1L, "分析",
                Map.of("system", "rules", "frames", List.of(
                        Map.of("seconds", 0D, "dataUrl", "data:image/jpeg;base64,AA=="),
                        Map.of("seconds", 1.5D, "dataUrl", "data:image/jpeg;base64,BB=="))), "analysis-1");
    }
    private String respond(ServerSocket server, int status, String response) throws Exception {
        try (var socket = server.accept()) {
            socket.setSoTimeout(5000);
            var input = socket.getInputStream(); var headers = new java.io.ByteArrayOutputStream();
            int c;
            while ((c = input.read()) != -1) {
                headers.write(c);
                if (headers.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) break;
            }
            String head = headers.toString(StandardCharsets.ISO_8859_1);
            var matcher = java.util.regex.Pattern.compile("(?im)^Content-Length: (\\d+)").matcher(head);
            int length = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
            String body = new String(input.readNBytes(length), StandardCharsets.UTF_8);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            var output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes); output.flush();
            return head + body;
        }
    }
}
