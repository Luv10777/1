package com.wuyao.growth.video.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.billing.BillingService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderRequest;
import com.wuyao.growth.common.gateway.VideoPlanHttpAdapter;
import com.wuyao.growth.common.task.TaskRepository;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.web.BizException;
import jakarta.validation.Validation;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 方案能看的参考图数量要和视频生成一样多：配置、请求校验、模型调用三处都得放行 30 张。 */
class VideoPlanImageLimitTest {
    private static final String IMAGE = "data:image/jpeg;base64,AA==";
    private final HttpClient http = mock(HttpClient.class);
    private final VideoPlanHttpAdapter adapter = new VideoPlanHttpAdapter(new VideoPlanProperties(), new ObjectMapper(), http);

    private static ProviderRequest request(int images) {
        return new ProviderRequest(ModelAlias.VIDEO_PLANNER, 3L, "出方案",
                Map.of("system", "系统提示", "images", Collections.nCopies(images, IMAGE)), "video-plan-1");
    }

    private static int violations(int images) {
        var ids = LongStream.rangeClosed(1, images).boxed().toList();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(new VideoPlanDtos.Create("plan-key-1", ids, false, null, null, null)).size();
        }
    }

    @Test void theLimitMatchesVideoReferenceImages() {
        var service = new VideoPlanService(mock(VideoPlanRepository.class), mock(AssetService.class), mock(TaskService.class),
                mock(TaskRepository.class), mock(AiGateway.class), mock(BillingService.class));
        assertThat(service.config().maxImages()).isEqualTo(30);
    }

    @Test void requestValidationAcceptsThirtyImagesAndRejectsMore() {
        assertThat(violations(30)).isZero();
        assertThat(violations(31)).isEqualTo(1);
    }

    @Test @SuppressWarnings("unchecked") void thirtyImagesReachTheModel() throws Exception {
        String content = "{\\\"plans\\\":[]}";
        byte[] reply = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"" + content + "\"}}]}").getBytes(StandardCharsets.UTF_8);
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenReturn(reply);
        var result = adapter.invoke(request(30));
        assertThat(result.output()).containsEntry("plans", List.of());
    }

    @Test void moreThanThirtyImagesNeverReachTheModel() {
        assertThatThrownBy(() -> adapter.invoke(request(31))).isInstanceOf(BizException.class).hasMessageContaining("参考图数量");
        verifyNoInteractions(http);
    }
}
