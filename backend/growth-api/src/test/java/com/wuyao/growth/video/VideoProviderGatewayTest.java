package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoProviderGatewayTest {
    @Test
    void everySelectedVideoAndAudioIsSignedAndSentInOrder() {
        var assets = mock(AssetService.class);
        when(assets.presignedReference(anyLong(), anyString())).thenAnswer(i -> "https://storage.example/" + i.getArgument(0));
        var request = new VideoDtos.Create("all-materials", "展示商品", List.of(1L, 2L), null,
                "SEEDANCE_2_5", "auto", 10, "720p", null, null, List.of(3L, 4L), List.of(5L, 6L));
        var body = new ObjectMapper().valueToTree(volcengineGateway(assets, mock(HttpClient.class)).buildSubmitRequest(request));
        var content = body.path("content");
        assertThat(content).hasSize(7);
        assertThat(content.get(3).path("video_url").path("url").asText()).isEqualTo("https://storage.example/3");
        assertThat(content.get(4).path("video_url").path("url").asText()).isEqualTo("https://storage.example/4");
        assertThat(content.get(5).path("audio_url").path("url").asText()).isEqualTo("https://storage.example/5");
        assertThat(content.get(6).path("audio_url").path("url").asText()).isEqualTo("https://storage.example/6");
        assertThat(content.get(5).path("role").asText()).isEqualTo("reference_audio");
        verify(assets).presignedReference(3L, "VIDEO");
        verify(assets).presignedReference(4L, "VIDEO");
        verify(assets).presignedReference(5L, "AUDIO");
        verify(assets).presignedReference(6L, "AUDIO");
        assertThatThrownBy(() -> gateway(assets, mock(HttpClient.class)).buildSubmitRequest(request))
                .hasMessageContaining("不支持多个参考视频或音频");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "CANCELLED", "canceled", "cancelled"})
    void submitAndPollNormalizeBothProviderCancellationSpellings(String providerStatus) throws Exception {
        var http = mock(HttpClient.class);
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    var response = mock(ClassicHttpResponse.class);
                    when(response.getCode()).thenReturn(200);
                    when(response.getEntity()).thenReturn(new StringEntity(
                            "{\"id\":\"job-cancel\",\"status\":\"" + providerStatus + "\"}", ContentType.APPLICATION_JSON));
                    return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
                });
        var gateway = gateway(mock(AssetService.class), http);

        assertThat(gateway.submit("{}", "submit-key").status()).isEqualTo("CANCELLED");
        var polled = gateway.poll("job-cancel", "SEEDANCE_2_0", "poll-key");
        assertThat(polled.status()).isEqualTo("CANCELLED");
        assertThat(polled.resultUrl()).isNull();
        verify(http, times(2)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void persistedJsonIsSentWithIdenticalUtf8BytesAndIdempotencyKeyOnEveryAttempt() throws Exception {
        var assets = mock(AssetService.class);
        var httpClient = mock(HttpClient.class);
        var response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(new StringEntity("{\"id\":\"job-1\",\"status\":\"queued\"}",
                ContentType.APPLICATION_JSON));
        var bytes = new ArrayList<byte[]>();
        var keys = new ArrayList<String>();
        when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    HttpPost request = invocation.getArgument(0);
                    bytes.add(request.getEntity().getContent().readAllBytes());
                    keys.add(request.getFirstHeader("Idempotency-Key").getValue());
                    return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
                });
        String persisted = "{ \"resolution\":\"480p\", \"prompt\":\"商品旋转\", \"model\":\"seedance\" }";
        var gateway = gateway(assets, httpClient);

        gateway.submit(persisted, "video-42-submit");
        gateway.submit(persisted, "video-42-submit");

        assertThat(bytes).hasSize(2);
        assertThat(bytes.get(0)).containsExactly(persisted.getBytes(StandardCharsets.UTF_8));
        assertThat(bytes.get(1)).containsExactly(bytes.get(0));
        assertThat(keys).containsExactly("video-42-submit", "video-42-submit");
        verifyNoInteractions(assets);
    }

    @Test
    void imageReferencesIncludeTheProviderRequiredRole() throws Exception {
        var assets = mock(AssetService.class);
        when(assets.presignedReference(7L, "IMAGE")).thenReturn("https://storage.example/reference.png");
        var httpClient = mock(HttpClient.class);
        var response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(200);
        when(response.getEntity()).thenReturn(new StringEntity("{\"id\":\"job-1\",\"status\":\"queued\"}",
                ContentType.APPLICATION_JSON));
        var requests = new ArrayList<ClassicHttpRequest>();
        when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
                });

        var gateway = gateway(assets, httpClient);
        gateway.submit(new VideoDtos.Create("request-1", "让商品缓慢旋转", List.of(7L), null,
                "SEEDANCE_2_0_MINI", "auto", 10, "480p"), "video-idem-1");

        assertThat(requests).hasSize(1);
        var body = new ObjectMapper().readTree(((HttpPost) requests.getFirst()).getEntity().getContent());
        JsonNode image = body.get("content").get(1);
        assertThat(image.get("type").asText()).isEqualTo("image_url");
        assertThat(image.get("role").asText()).isEqualTo("reference_image");
        assertThat(image.get("image_url").get("url").asText()).isEqualTo("https://storage.example/reference.png");
    }

    @Test
    void onlyTransientProviderStatusesAreRetryable() {
        assertThat(new VideoProviderGateway.ProviderHttpException(400, "bad request").retryable()).isFalse();
        assertThat(new VideoProviderGateway.ProviderHttpException(409, "conflict").retryable()).isFalse();
        assertThat(new VideoProviderGateway.ProviderHttpException(429, "rate limited").retryable()).isTrue();
        assertThat(new VideoProviderGateway.ProviderHttpException(503, "unavailable").retryable()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"400, false", "404, false", "410, false", "408, true", "429, true", "503, true"})
    void completedJobContentEndpointPreservesTheHttpRetryClassification(int status, boolean retryable) throws Exception {
        var http = mock(HttpClient.class);
        var poll = mock(ClassicHttpResponse.class);
        when(poll.getCode()).thenReturn(200);
        when(poll.getEntity()).thenReturn(new StringEntity("{\"status\":\"completed\"}", ContentType.APPLICATION_JSON));
        var content = mock(ClassicHttpResponse.class);
        when(content.getCode()).thenReturn(status);
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0);
            return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(
                    request.getRequestUri().endsWith("/content") ? content : poll);
        });
        assertThatThrownBy(() -> gateway(mock(AssetService.class), http).poll("job-1", "SEEDANCE_2_0", "poll-key"))
                .isInstanceOfSatisfying(VideoProviderGateway.ProviderHttpException.class, e -> {
                    assertThat(e.status()).isEqualTo(status);
                    assertThat(e.retryable()).isEqualTo(retryable);
                });
    }

    @ParameterizedTest
    @CsvSource({"SEEDANCE_2_0, seedance-2.0, shared-key", "SEEDANCE_2_5, seedance-2.5, shared-key",
            "SEEDANCE_2_0_FAST, seedance-2.0-fast, fast-key", "SEEDANCE_2_0_MINI, seedance-2.0-mini, mini-key"})
    void volcengineSubmitAndPollUseTheSameModelCredential(String alias, String model, String key) throws Exception {
        var http = mock(HttpClient.class);
        var requests = new ArrayList<ClassicHttpRequest>();
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    ClassicHttpRequest request = invocation.getArgument(0);
                    requests.add(request);
                    var response = mock(ClassicHttpResponse.class);
                    when(response.getCode()).thenReturn(200);
                    when(response.getEntity()).thenReturn(new StringEntity(request instanceof HttpPost
                            ? "{\"id\":\"job-1\",\"status\":\"queued\"}"
                            : "{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://haomaotoken.com/video.mp4\"},\"usage\":{\"completion_tokens\":1234}}",
                            ContentType.APPLICATION_JSON));
                    return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
                });
        var gateway = volcengineGateway(mock(AssetService.class), http);
        // Persisted JSON must remain byte-for-byte identical even when selecting a credential.
        String persisted = "{ \"model\":\"" + model + "\", \"content\":[{\"type\":\"text\",\"text\":\"旋转\"}], \"duration\":5 }";

        assertThat(gateway.submit(persisted, "submit-key").provider()).isEqualTo("haomaotoken");
        var result = gateway.poll("job-1", alias, "poll-key");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.resultUrl()).isEqualTo("https://haomaotoken.com/video.mp4");
        assertThat(result.billingTokens()).isEqualTo(1234L);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).getRequestUri()).endsWith("/api/v3/contents/generations/tasks");
        assertThat(requests.get(1).getRequestUri()).endsWith("/api/v3/contents/generations/tasks/job-1");
        for (var request : requests) assertThat(request.getFirstHeader("Authorization").getValue()).isEqualTo("Bearer " + key);
        assertThat(requests.get(0).getFirstHeader("Idempotency-Key").getValue()).isEqualTo("submit-key");
        assertThat(requests.get(1).getFirstHeader("Idempotency-Key").getValue()).isEqualTo("poll-key");
        assertThat(((HttpPost) requests.getFirst()).getEntity().getContent().readAllBytes())
                .containsExactly(persisted.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pollAcceptsVideoUsageAliasesFromProviderResponse() throws Exception {
        var http = mock(HttpClient.class);
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    var response = mock(ClassicHttpResponse.class);
                    when(response.getCode()).thenReturn(200);
                    when(response.getEntity()).thenReturn(new StringEntity(
                            "{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://haomaotoken.com/video.mp4\"},\"usage\":{\"video_tokens\":\"4321\"}}",
                            ContentType.APPLICATION_JSON));
                    return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
                });

        var result = volcengineGateway(mock(AssetService.class), http)
                .poll("job-1", "SEEDANCE_2_0", "poll-key");

        assertThat(result.billingTokens()).isEqualTo(4321L);
    }

    @Test
    void volcengineBuildsMultimodalContentAndUsesDurationAndRatioFields() {
        var assets = mock(AssetService.class);
        when(assets.presignedReference(7L, "IMAGE")).thenReturn("https://storage.example/image.png");
        when(assets.presignedReference(8L, "VIDEO")).thenReturn("https://storage.example/video.mp4");
        var gateway = volcengineGateway(assets, mock(HttpClient.class));

        var body = new ObjectMapper().valueToTree(gateway.buildSubmitRequest(new VideoDtos.Create(
                "request-1", "商品旋转", List.of(7L), 8L, "SEEDANCE_2_0", "9:16", 5, "4K")));

        assertThat(body.path("model").asText()).isEqualTo("seedance-2.0");
        assertThat(body.path("duration").asInt()).isEqualTo(5);
        assertThat(body.path("ratio").asText()).isEqualTo("9:16");
        assertThat(body.path("resolution").asText()).isEqualTo("4k");
        assertThat(body.has("seconds") || body.has("mode") || body.has("prompt") || body.has("input_reference")).isFalse();
        assertThat(body.path("content").size()).isEqualTo(3);
        assertThat(body.path("content").get(0).path("text").asText()).isEqualTo("商品旋转");
        assertThat(body.path("content").get(1).path("image_url").path("url").asText()).endsWith("/image.png");
        assertThat(body.path("content").get(2).path("type").asText()).isEqualTo("video_url");
        assertThat(body.path("content").get(2).path("role").asText()).isEqualTo("reference_video");
        assertThat(body.path("content").get(2).path("video_url").path("url").asText()).endsWith("/video.mp4");
    }

    @Test
    void volcengineTextOnlyRequestOmitsAutomaticRatioAndDoesNotNeedAssets() {
        var assets = mock(AssetService.class);
        var body = new ObjectMapper().valueToTree(volcengineGateway(assets, mock(HttpClient.class))
                .buildSubmitRequest(new VideoDtos.Create("request-1", "商品旋转", List.of(), null,
                        "SEEDANCE_2_0_MINI", "auto", 5, "480p")));
        assertThat(body.path("content").size()).isEqualTo(1);
        assertThat(body.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(body.has("ratio")).isFalse();
        verifyNoInteractions(assets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"succeeded\"}",
            "{\"status\":\"succeeded\",\"content\":{\"video_url\":\"http://haomaotoken.com/video.mp4\"}}",
            "{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://127.0.0.1/video.mp4\"}}"})
    void completedVolcengineTaskCannotImportAMissingOrUnsafeResult(String responseBody) throws Exception {
        var http = mock(HttpClient.class);
        jsonResponse(http, responseBody);
        assertThatThrownBy(() -> volcengineGateway(mock(AssetService.class), http)
                .poll("job-1", "SEEDANCE_2_0_MINI", "poll-key")).isInstanceOf(RuntimeException.class);
        verify(http, times(1)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void volcengineCanReadWrappedContentArraysAndTotalTokenUsage() throws Exception {
        var http = mock(HttpClient.class);
        jsonResponse(http, "{\"data\":{\"status\":\"succeeded\",\"content\":[{\"type\":\"video_url\",\"video_url\":{\"url\":\"https://haomaotoken.com/video.mp4\"}}],\"usage\":{\"total_tokens\":900}}}");
        var result = volcengineGateway(mock(AssetService.class), http).poll("job-1", "SEEDANCE_2_0_FAST", "poll-key");
        assertThat(result.resultUrl()).isEqualTo("https://haomaotoken.com/video.mp4");
        assertThat(result.billingTokens()).isEqualTo(900L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "running", "processing", "failed", "cancelled", "expired"})
    void unfinishedOrFailedVolcengineTasksDoNotFetchVideoContent(String status) throws Exception {
        var http = mock(HttpClient.class);
        jsonResponse(http, "{\"status\":\"" + status + "\",\"error\":{\"code\":\"GenerationFailed\",\"message\":\"请调整提示词\"}}");
        var result = volcengineGateway(mock(AssetService.class), http).poll("job-1", "SEEDANCE_2_0_FAST", "poll-key");
        assertThat(result.resultUrl()).isNull();
        assertThat(result.error()).isEqualTo("GenerationFailed: 请调整提示词");
        assertThat(result.status()).isEqualTo(switch (status) {
            case "expired" -> "FAILED";
            case "processing" -> "RUNNING";
            default -> status.toUpperCase();
        });
        verify(http, times(1)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void providerErrorsNeverReturnModelCredentialsToLogsOrTheUser() throws Exception {
        var http = mock(HttpClient.class);
        jsonResponse(http, "{\"data\":{\"status\":\"failed\",\"error\":{\"message\":\"invalid shared-key fast-key mini-key\"}}}");
        var gateway = volcengineGateway(mock(AssetService.class), http);
        assertThat(gateway.poll("job-1", "SEEDANCE_2_0_MINI", "poll-key").error())
                .isEqualTo("invalid [redacted] [redacted] [redacted]");
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    var response = mock(ClassicHttpResponse.class);
                    when(response.getCode()).thenReturn(401);
                    when(response.getEntity()).thenReturn(new StringEntity("{\"error\":{\"message\":\"invalid mini-key\"}}",
                            ContentType.APPLICATION_JSON));
                    return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
                });
        assertThatThrownBy(() -> gateway.submit("{\"model\":\"seedance-2.0-mini\"}", "submit-key"))
                .isInstanceOf(VideoProviderGateway.ProviderHttpException.class)
                .hasMessageContaining("[redacted]").hasMessageNotContaining("mini-key");
    }

    @Test
    void modelCredentialsWorkWithoutACommonKeyAndAnUnconfiguredModelFailsBeforeHttp() throws Exception {
        var http = mock(HttpClient.class);
        jsonResponse(http, "{\"id\":\"job-1\",\"status\":\"queued\"}");
        var gateway = volcengineGateway(mock(AssetService.class), http);
        ReflectionTestUtils.setField(gateway, "apiKey", "");
        assertThat(gateway.configured()).isTrue();
        gateway.submit("{\"model\":\"seedance-2.0-mini\"}", "submit-key");
        assertThatThrownBy(() -> gateway.submit("{\"model\":\"seedance-2.5\"}", "submit-key"))
                .hasMessageContaining("密钥尚未配置");
        assertThatThrownBy(() -> gateway.submit("{\"model\":\"unknown\"}", "submit-key"))
                .hasMessageContaining("不支持");
        assertThatThrownBy(() -> gateway.poll("job-1", "SEEDANCE_2_0", "poll-key"))
                .hasMessageContaining("密钥尚未配置");
        verify(http, times(1)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void billedQuotaReadsTheRelayTaskRecordWithTheAccountAccessToken() throws Exception {
        var http = mock(HttpClient.class);
        var requests = new ArrayList<ClassicHttpRequest>();
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            var response = mock(ClassicHttpResponse.class);
            when(response.getCode()).thenReturn(200);
            when(response.getEntity()).thenReturn(new StringEntity("{\"success\":true,\"data\":{\"items\":["
                    + "{\"task_id\":\"task_other\",\"status\":\"SUCCESS\",\"quota\":1},"
                    + "{\"task_id\":\"task_abc\",\"status\":\"SUCCESS\",\"quota\":2185000}],\"total\":2}}", ContentType.APPLICATION_JSON));
            return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
        });
        var gateway = relayUsageGateway(http);

        assertThat(gateway.billedQuota("task_abc")).isEqualTo(2185000L);
        var request = requests.getFirst();
        assertThat(request.getRequestUri()).isEqualTo("/api/task/self?p=1&page_size=10&task_id=task_abc");
        assertThat(request.getFirstHeader("Authorization").getValue()).isEqualTo("Bearer access-token");
        assertThat(request.getFirstHeader("New-Api-User").getValue()).isEqualTo("1001");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":true,\"data\":{\"items\":[{\"task_id\":\"task_abc\",\"status\":\"IN_PROGRESS\",\"quota\":2185000}]}}",
            "{\"success\":true,\"data\":{\"items\":[{\"task_id\":\"task_abc\",\"status\":\"SUCCESS\",\"quota\":0}]}}",
            "{\"success\":true,\"data\":{\"items\":[]}}",
            "{\"success\":false,\"message\":\"Unauthorized\"}",
            "not json"
    })
    void billedQuotaIsAbsentUnlessTheRelayRecordsAFinishedCharge(String body) throws Exception {
        var http = mock(HttpClient.class);
        jsonResponse(http, body);
        assertThat(relayUsageGateway(http).billedQuota("task_abc")).isNull();
    }

    @Test
    void billedQuotaIsAbsentOnRelayErrorsOrWithoutAnAccessToken() throws Exception {
        var http = mock(HttpClient.class);
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            var response = mock(ClassicHttpResponse.class);
            when(response.getCode()).thenReturn(401);
            when(response.getEntity()).thenReturn(new StringEntity("{\"success\":false}", ContentType.APPLICATION_JSON));
            return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
        });
        assertThat(relayUsageGateway(http).billedQuota("task_abc")).isNull();

        var unconfigured = mock(HttpClient.class);
        assertThat(volcengineGateway(mock(AssetService.class), unconfigured).billedQuota("task_abc")).isNull();
        verifyNoInteractions(unconfigured);
    }

    private VideoProviderGateway relayUsageGateway(HttpClient http) {
        var gateway = volcengineGateway(mock(AssetService.class), http);
        ReflectionTestUtils.setField(gateway, "usageAccessToken", "access-token");
        ReflectionTestUtils.setField(gateway, "usageUserId", "1001");
        ReflectionTestUtils.setField(gateway, "usageTaskPath", "/api/task/self");
        return gateway;
    }

    private void jsonResponse(HttpClient http, String body) throws Exception {
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    var response = mock(ClassicHttpResponse.class);
                    when(response.getCode()).thenReturn(200);
                    when(response.getEntity()).thenReturn(new StringEntity(body, ContentType.APPLICATION_JSON));
                    return ((HttpClientResponseHandler<?>) invocation.getArgument(1)).handleResponse(response);
                });
    }

    private VideoProviderGateway volcengineGateway(AssetService assets, HttpClient http) {
        var gateway = gateway(assets, http);
        ReflectionTestUtils.setField(gateway, "baseUrl", "https://haomaotoken.com");
        ReflectionTestUtils.setField(gateway, "protocol", "VOLCENGINE");
        ReflectionTestUtils.setField(gateway, "providerName", "haomaotoken");
        ReflectionTestUtils.setField(gateway, "submitPath", "/api/v3/contents/generations/tasks");
        ReflectionTestUtils.setField(gateway, "pollPath", "/api/v3/contents/generations/tasks/{jobId}");
        ReflectionTestUtils.setField(gateway, "apiKey", "shared-key");
        ReflectionTestUtils.setField(gateway, "seedance20FastApiKey", "fast-key");
        ReflectionTestUtils.setField(gateway, "seedance20MiniApiKey", "mini-key");
        return gateway;
    }

    private VideoProviderGateway gateway(AssetService assets, HttpClient httpClient) {
        var gateway = new VideoProviderGateway(new ObjectMapper(), assets, httpClient);
        ReflectionTestUtils.setField(gateway, "baseUrl", "https://api.onlyrouter.ai");
        ReflectionTestUtils.setField(gateway, "apiKey", "test-key");
        ReflectionTestUtils.setField(gateway, "submitPath", "/v1/videos");
        ReflectionTestUtils.setField(gateway, "pollPath", "/v1/videos/{jobId}");
        ReflectionTestUtils.setField(gateway, "contentPath", "/v1/videos/{jobId}/content");
        ReflectionTestUtils.setField(gateway, "protocol", "CHAT_COMPLETIONS");
        ReflectionTestUtils.setField(gateway, "providerName", "onlyrouter");
        ReflectionTestUtils.setField(gateway, "seedance25", "seedance-2.5");
        ReflectionTestUtils.setField(gateway, "seedance20", "seedance-2.0");
        ReflectionTestUtils.setField(gateway, "seedance20Fast", "seedance-2.0-fast");
        ReflectionTestUtils.setField(gateway, "seedance20Mini", "seedance-2.0-mini");
        return gateway;
    }
}
