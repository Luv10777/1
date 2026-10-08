package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import com.wuyao.growth.common.task.Task;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoAnalysisSynthesizeHandlerTest {
    private final VideoAnalysisService service = mock(VideoAnalysisService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final VideoAnalysisSynthesizeHandler handler = new VideoAnalysisSynthesizeHandler(service, storage, gateway, new ObjectMapper());
    private final Task task = new Task();

    private void ready(String mode) {
        task.setTenantId(1L); task.setPayload(Map.of("analysisId", 2L));
        when(service.begin(2L, task, "ANALYZING", 65)).thenReturn(true);
        var analysis = new VideoAnalysis();
        analysis.setMode(mode); analysis.setDurationMs(12000); analysis.setWidth(1080); analysis.setHeight(1920);
        analysis.setReverseNeed("重点复刻"); analysis.setFrames(List.of(new VideoAnalysis.Frame(0, "t1/frame.jpg")));
        analysis.setAudioAnalysis(VideoAudioOutputTest.valid());
        analysis.getAudioAnalysis().put("status", "ANALYZED");
        when(service.processing(2L)).thenReturn(analysis);
        when(storage.read("t1/frame.jpg", 4 * 1024 * 1024)).thenReturn(new byte[]{1, 2, 3});
        when(service.completed(eq(2L), eq(task), anyMap())).thenReturn(true);
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "TEST_VISION", null, VideoAnalysisOutputTest.validFor(mode), null, null));
    }

    @Test void aiModeUsesTheGenerationContractAndKeepsAudioEvidence() {
        ready("ai");
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
        var request = ArgumentCaptor.forClass(ProviderRequest.class); verify(gateway).invokeReal(request.capture());
        assertThat(request.getValue().options().get("system").toString()).contains("当前模式是 AI 视频反推", "首帧图片").doesNotContain("当前模式是实拍视频拆解");
        var report = ArgumentCaptor.forClass(Map.class); verify(service).completed(eq(2L), eq(task), report.capture());
        assertThat(report.getValue()).containsEntry("audioAnalyzed", true).containsEntry("schemaVersion", 3);
    }

    @Test void realModeUsesTheFilmingAndAiConversionContract() {
        ready("real");
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
        var request = ArgumentCaptor.forClass(ProviderRequest.class); verify(gateway).invokeReal(request.capture());
        assertThat(request.getValue().options().get("system").toString()).contains("当前模式是实拍视频拆解", "低成本替代方法", "aiWorkflow").doesNotContain("当前模式是 AI 视频反推");
    }

    @Test void realModeCannotStoreAnAiOnlyResponseAsACompleteReport() {
        ready("real");
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "TEST_VISION", null, VideoAnalysisOutputTest.validFor("ai"), null, null));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(NonRetryableTaskException.class);
        verify(service, never()).completed(anyLong(), any(), anyMap());
        verify(service).failed(eq(2L), eq(task), anyString());
    }

    @Test void EnglishPromptsFailTheTaskInsteadOfBecomingAUserVisibleReport() {
        ready("ai");
        var output = VideoAnalysisOutputTest.validFor("ai");
        output.put("prompt", "A cinematic shot of a red object near the window");
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "TEST_VISION", null, output, null, null));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOfSatisfying(NonRetryableTaskException.class,
                error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_LANGUAGE"));
        verify(service, never()).completed(anyLong(), any(), anyMap());
        verify(service).failed(2L, task, "模型未返回中文复刻提示词，请重新分析");
    }

    @Test void originalProductLeakageCannotBeSavedAsASuccessfulAnalysis() {
        ready("ai");
        var output = VideoAnalysisOutputTest.productVideo("ai");
        output.put("reuseScript", "镜头一：拿起白杯，保持某牌的包装和杯子颜色。");
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "TEST_VISION", null, output, null, null));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOfSatisfying(NonRetryableTaskException.class,
                error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_PRODUCT"));
        verify(service, never()).completed(anyLong(), any(), anyMap());
        verify(service).failed(2L, task, "模型未将原商品替换为“如图中产品”，请重新分析");
    }

    @Test void expiredWorkersCannotAnalyzeOrWriteResults() {
        task.setPayload(Map.of("analysisId", 2L));
        assertThat(handler.handle(task)).containsEntry("status", "STALE");
        verifyNoInteractions(storage, gateway);
        verify(service, never()).completed(anyLong(), any(), anyMap());
    }
}
