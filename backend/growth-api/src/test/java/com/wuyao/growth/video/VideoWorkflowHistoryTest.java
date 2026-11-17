package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetRepository;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.asset.VideoMediaProbe;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 创作记录列表：已完成的视频带成片链接（侧栏显示首帧），其余不签。 */
class VideoWorkflowHistoryTest {
    private final VideoWorkflowRepository workflows = mock(VideoWorkflowRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private VideoWorkflowService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        service = new VideoWorkflowService(workflows, mock(VideoProviderJobRepository.class), mock(AssetRepository.class), mock(AssetService.class),
                storage, mock(TaskService.class), new ObjectMapper(), mock(TenantRateLimiter.class), mock(HttpClient.class), mock(VideoMediaProbe.class),
                mock(com.wuyao.growth.common.metrics.VideoMetrics.class), mock(com.wuyao.growth.billing.BillingService.class), mock(com.wuyao.growth.billing.VideoPricingService.class));
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void finishedVideosCarryASignedOutputLink() {
        var done = workflow(1L, "SUCCEEDED", "t7/generated-video/1/output.mp4");
        var running = workflow(2L, "RUNNING", null);
        var failed = workflow(3L, "FAILED", "t7/generated-video/3/output.mp4");
        when(workflows.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(done, running, failed)));
        when(storage.presignGet("t7/generated-video/1/output.mp4", Duration.ofMinutes(30))).thenReturn("https://storage.test/1.mp4");

        var items = service.history(0, 20).items();

        assertThat(items).extracting(VideoDtos.History::outputUrl).containsExactly("https://storage.test/1.mp4", null, null);
        verify(storage, times(1)).presignGet(anyString(), any(Duration.class));
    }

    private VideoWorkflow workflow(Long id, String status, String outputKey) {
        var w = new VideoWorkflow();
        w.setId(id);
        w.setTenantId(7L);
        w.setStatus(status);
        w.setOutputStorageKey(outputKey);
        w.setRequest(new VideoDtos.Create("history-" + id, "新品短视频", List.of(), null, "SEEDANCE_2_0_MINI", "auto", 5, "480p"));
        w.setResolution("480p");
        w.setRatio("auto");
        w.setDurationSeconds(5);
        return w;
    }
}
