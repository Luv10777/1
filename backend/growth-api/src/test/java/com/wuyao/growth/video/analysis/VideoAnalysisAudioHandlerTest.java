package com.wuyao.growth.video.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.gateway.*;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.Task;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoAnalysisAudioHandlerTest {
    private final VideoAnalysisService service = mock(VideoAnalysisService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final VideoAnalysisAudioHandler handler = new VideoAnalysisAudioHandler(service, storage, gateway,
            new VideoAnalysisProperties(), new ObjectMapper());
    private final Task task = new Task();

    private void ready() {
        task.setTenantId(1L); task.setPayload(Map.of("analysisId", 2L));
        when(service.begin(2L, task, "ANALYZING_AUDIO", 50)).thenReturn(true);
        var analysis = new VideoAnalysis(); analysis.setDurationMs(12000); analysis.setAudioStorageKey("t1/audio.wav");
        when(service.processing(2L)).thenReturn(analysis);
        when(service.audioReady(eq(2L), eq(task), anyMap())).thenReturn(true);
        when(gateway.configured(ModelAlias.AUDIO_ANALYZER)).thenReturn(true);
        when(storage.read(eq("t1/audio.wav"), anyInt())).thenReturn(new byte[]{1, 2, 3});
    }

    @Test void storesTheValidatedReportBeforeAdvancingToVisualSynthesis() {
        ready();
        when(gateway.invokeReal(any())).thenReturn(new ProviderResult(true, "TEST_AUDIO", null, VideoAudioOutputTest.valid(), null, null));
        assertThat(handler.handle(task)).containsEntry("audioStatus", "ANALYZED");
        var request = ArgumentCaptor.forClass(ProviderRequest.class); verify(gateway).invokeReal(request.capture());
        assertThat(request.getValue().alias()).isEqualTo(ModelAlias.AUDIO_ANALYZER);
        assertThat(request.getValue().options()).containsEntry("audioDataUrl", "data:audio/wav;base64,AQID");
        var report = ArgumentCaptor.forClass(Map.class); verify(service).audioReady(eq(2L), eq(task), report.capture());
        assertThat(report.getValue()).containsEntry("status", "ANALYZED").containsKey("transcript");
    }

    @Test void audioFailureStillAdvancesAndCannotExposeUpstreamDetails() {
        ready(); when(gateway.invokeReal(any())).thenThrow(new IllegalStateException("private-key provider-debug"));
        assertThat(handler.handle(task)).containsEntry("audioStatus", "FAILED");
        var report = ArgumentCaptor.forClass(Map.class); verify(service).audioReady(eq(2L), eq(task), report.capture());
        assertThat(report.getValue().toString()).doesNotContain("private-key", "provider-debug");
    }

    @Test void missingAudioConfigurationSkipsTheModelCall() {
        ready(); when(gateway.configured(ModelAlias.AUDIO_ANALYZER)).thenReturn(false);
        assertThat(handler.handle(task)).containsEntry("audioStatus", "NOT_CONFIGURED");
        verify(gateway, never()).invokeReal(any()); verifyNoInteractions(storage);
    }

    @Test void expiredWorkersCannotCallTheAudioModel() {
        task.setPayload(Map.of("analysisId", 2L));
        assertThat(handler.handle(task)).containsEntry("status", "STALE");
        verifyNoInteractions(storage, gateway);
    }
}
