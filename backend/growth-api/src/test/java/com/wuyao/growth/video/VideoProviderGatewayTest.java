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
        var polled = gateway.poll("job-cancel", "poll-key");
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
        assertThatThrownBy(() -> gateway(mock(AssetService.class), http).poll("job-1", "poll-key"))
                .isInstanceOfSatisfying(VideoProviderGateway.ProviderHttpException.class, e -> {
                    assertThat(e.status()).isEqualTo(status);
                    assertThat(e.retryable()).isEqualTo(retryable);
                });
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
        return gateway;
    }
}
