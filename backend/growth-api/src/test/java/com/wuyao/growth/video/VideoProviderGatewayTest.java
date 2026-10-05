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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoProviderGatewayTest {
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

    private VideoProviderGateway gateway(AssetService assets, HttpClient httpClient) {
        var gateway = new VideoProviderGateway(new ObjectMapper(), assets, httpClient);
        ReflectionTestUtils.setField(gateway, "baseUrl", "https://api.onlyrouter.ai");
        ReflectionTestUtils.setField(gateway, "apiKey", "test-key");
        ReflectionTestUtils.setField(gateway, "submitPath", "/v1/videos");
        ReflectionTestUtils.setField(gateway, "protocol", "CHAT_COMPLETIONS");
        ReflectionTestUtils.setField(gateway, "providerName", "onlyrouter");
        return gateway;
    }
}
