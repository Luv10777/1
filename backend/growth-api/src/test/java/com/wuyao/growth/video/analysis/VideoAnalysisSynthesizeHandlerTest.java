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
        when(service.begin(2L, task, "ANALYZING", 80)).thenReturn(true);
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
        assertThat(report.getValue()).containsEntry("audioAnalyzed", true).containsEntry("schemaVersion", 4);
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

    @Test void fabricatedDialogueCannotBeSavedAsASuccessfulAnalysis() {
        ready("ai");
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "TEST_VISION", null, VideoAnalysisEditableContentTest.linked("ai"), null, null));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOfSatisfying(NonRetryableTaskException.class,
                error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_EDITABLE"));
        verify(service, never()).completed(anyLong(), any(), anyMap());
        verify(service).failed(2L, task, "品牌、口播或字幕整理失败：口播原文或出现时间与已识别音频不一致，请重新分析");
        verify(gateway, times(2)).invokeReal(any());
    }

    @Test void equivalentLabelsDoNotRequireAnotherModelCall() {
        ready("ai");
        var output = editable("口播", "音频", "The password is silver lantern nine.");
        when(gateway.invokeReal(any())).thenReturn(response(output));
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
        verify(gateway).invokeReal(any());
        verify(service, never()).begin(2L, task, "ANALYZING", 80);
        var report = ArgumentCaptor.forClass(Map.class); verify(service).completed(eq(2L), eq(task), report.capture());
        assertThat(((List<Map<String, Object>>) report.getValue().get("editableContent")).getFirst())
                .containsEntry("kind", "dialogue").containsEntry("source", "audio");
    }

    @Test void unsupportedLabelsGetOneTargetedRepairUsingTheSameFramesAndAudio() {
        ready("ai");
        var output = editable("unsupported", "audio", "The password is silver lantern nine.");
        var fixed = editable("dialogue", "audio", "The password is silver lantern nine.");
        var patch = new java.util.HashMap<String, Object>();
        for (String key : List.of("prompt", "reuseScript", "editableContent")) patch.put(key, fixed.get(key));
        patch.put("summary", "不应修改原有画面观察"); patch.put("productReferences", List.of("不应替换商品检查词"));
        when(gateway.invokeReal(any())).thenReturn(response(output), response(patch));
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
        var requests = ArgumentCaptor.forClass(ProviderRequest.class); verify(gateway, times(2)).invokeReal(requests.capture());
        var initial = requests.getAllValues().getFirst(); var repair = requests.getAllValues().get(1);
        assertThat(repair.idempotencyKey()).isEqualTo("video-analysis-2-editable-repair").isNotEqualTo(initial.idempotencyKey());
        assertThat(repair.alias()).isEqualTo(initial.alias()); assertThat(repair.tenantId()).isEqualTo(initial.tenantId());
        assertThat(repair.options().get("frames")).isEqualTo(initial.options().get("frames"));
        assertThat(repair.prompt()).contains("可修改内容类型不正确", "The password is silver lantern nine.", "unsupported");
        assertThat(repair.options().get("system").toString()).contains("仅返回包含 prompt、reuseScript、editableContent");
        var report = ArgumentCaptor.forClass(Map.class); verify(service).completed(eq(2L), eq(task), report.capture());
        assertThat(report.getValue()).containsEntry("summary", output.get("summary")).containsEntry("productReferences", List.of())
                .containsEntry("audioAnalyzed", true);
        verify(service, never()).failed(anyLong(), any(), anyString());
    }

    @Test void missingTemplateLinksCanBeRepairedWithoutReanalyzingTheVideo() {
        ready("ai");
        var output = editable("dialogue", "audio", "The password is silver lantern nine.");
        output.put("reuseScript", VideoAnalysisOutputTest.validFor("ai").get("reuseScript"));
        when(gateway.invokeReal(any())).thenReturn(response(output), response(editable("dialogue", "audio", "The password is silver lantern nine.")));
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
        verify(gateway, times(2)).invokeReal(any());
    }

    @Test void repairCanCorrectSpeechTimingOnlyWhenItMatchesTheActualTranscript() {
        ready("ai");
        var output = editable("dialogue", "audio", "The password is silver lantern nine.");
        var item = new java.util.HashMap<>(((List<Map<String, Object>>) output.get("editableContent")).getFirst());
        item.put("start", 8); item.put("end", 10); output.put("editableContent", List.of(item));
        when(gateway.invokeReal(any())).thenReturn(response(output), response(editable("dialogue", "audio", "The password is silver lantern nine.")));
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
        verify(gateway, times(2)).invokeReal(any());
        var report = ArgumentCaptor.forClass(Map.class); verify(service).completed(eq(2L), eq(task), report.capture());
        assertThat(((List<Map<String, Object>>) report.getValue().get("editableContent")).getFirst())
                .containsEntry("start", 0D).containsEntry("end", 3D).containsEntry("original", "The password is silver lantern nine.");
    }

    @Test void realModeRepairsBrandFieldsWhileKeepingItsCompleteFilmingGuide() {
        ready("real");
        var output = VideoAnalysisOutputTest.productVideo("real");
        output.put("prompt", output.get("prompt") + " {{edit:e1}}");
        output.put("editableContent", List.of(Map.of("id", "e1", "kind", "brand", "source", "both", "label", "品牌",
                "original", "某牌", "start", 0, "end", 3)));
        var patch = Map.of("prompt", output.get("prompt"), "reuseScript", output.get("reuseScript"), "editableContent",
                List.of(Map.of("id", "e1", "kind", "brand", "source", "video", "label", "品牌", "original", "某牌", "start", 0, "end", 3)));
        when(gateway.invokeReal(any())).thenReturn(response(output), response(patch));
        assertThat(handler.handle(task)).containsEntry("status", "SUCCEEDED");
        verify(gateway, times(2)).invokeReal(any());
        var report = ArgumentCaptor.forClass(Map.class); verify(service).completed(eq(2L), eq(task), report.capture());
        assertThat(report.getValue()).containsEntry("reuseScript", output.get("reuseScript")).containsEntry("recreation", output.get("recreation"))
                .containsEntry("shots", output.get("shots"));
    }

    @Test void aRepairCannotSilentlyDiscardAllPreviouslyIdentifiedOriginalWords() {
        ready("ai");
        when(gateway.invokeReal(any())).thenReturn(response(editable("unsupported", "audio", "The password is silver lantern nine.")),
                response(VideoAnalysisOutputTest.validFor("ai")));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(NonRetryableTaskException.class).hasMessageContaining("丢失了可修改原文");
        verify(gateway, times(2)).invokeReal(any());
        verify(service, never()).completed(anyLong(), any(), anyMap());
    }

    @Test void aRepairCannotBypassTheOriginalProductCheckOrReplaceObservations() {
        ready("ai");
        var output = VideoAnalysisOutputTest.productVideo("ai");
        output.put("prompt", output.get("prompt") + " {{edit:e1}}");
        output.put("reuseScript", output.get("reuseScript") + " {{edit:e1}}");
        output.put("editableContent", List.of(Map.of("id", "e1", "kind", "unsupported", "source", "video", "label", "品牌",
                "original", "某牌", "start", 0, "end", 3)));
        var patch = new java.util.HashMap<>(output);
        patch.put("editableContent", List.of(Map.of("id", "e1", "kind", "brand", "source", "video", "label", "品牌",
                "original", "某牌", "start", 0, "end", 3)));
        patch.put("prompt", output.get("prompt") + " 保留某牌"); patch.put("productReferences", List.of());
        when(gateway.invokeReal(any())).thenReturn(response(output), response(patch));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOfSatisfying(NonRetryableTaskException.class,
                error -> assertThat(error.errorCode()).isEqualTo("VIDEO_ANALYSIS_PRODUCT"));
        verify(gateway, times(2)).invokeReal(any());
        verify(service, never()).completed(anyLong(), any(), anyMap());
    }

    @Test void malformedRepairResponsesFailAfterOneCorrectionAttempt() {
        ready("ai");
        when(gateway.invokeReal(any())).thenReturn(response(editable("unsupported", "audio", "The password is silver lantern nine.")),
                response(Map.of("editableContent", List.of())));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(NonRetryableTaskException.class);
        verify(gateway, times(2)).invokeReal(any());
        verify(service, never()).completed(anyLong(), any(), anyMap());
    }

    @Test void anExpiredLeasePreventsTheCorrectionCallAndResultWrites() {
        ready("ai"); when(service.begin(2L, task, "ANALYZING", 80)).thenReturn(false);
        when(gateway.invokeReal(any())).thenReturn(response(editable("unsupported", "audio", "The password is silver lantern nine.")));
        assertThat(handler.handle(task)).containsEntry("status", "STALE");
        verify(gateway).invokeReal(any());
        verify(service, never()).completed(anyLong(), any(), anyMap());
        verify(service, never()).failed(anyLong(), any(), anyString());
    }

    private Map<String, Object> editable(String kind, String source, String original) {
        var output = VideoAnalysisOutputTest.validFor("ai");
        output.put("prompt", output.get("prompt") + " 口播安排：{{edit:e1}}");
        output.put("reuseScript", output.get("reuseScript") + " {{edit:e1}}");
        output.put("editableContent", List.of(Map.of("id", "e1", "kind", kind, "source", source, "label", "开场口播",
                "original", original, "start", 0, "end", 3)));
        return output;
    }

    private ProviderResult response(Map<String, Object> output) {
        return new ProviderResult(true, "TEST_VISION", null, output, null, null);
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
