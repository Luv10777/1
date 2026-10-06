package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.*;
import com.wuyao.growth.common.metrics.VideoMetrics;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoWorkflowQaTest {
    private static final String KEY = "t7/generated-video/42/output.mp4";
    private final VideoWorkflowRepository workflows = mock(VideoWorkflowRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TenantRateLimiter permits = mock(TenantRateLimiter.class);
    private final VideoMediaProbe probe = mock(VideoMediaProbe.class);
    private VideoWorkflowService service;
    private VideoWorkflow workflow;
    private Asset output;
    private Task task;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        service = new VideoWorkflowService(workflows, mock(VideoProviderJobRepository.class), assets, mock(AssetService.class),
                storage, tasks, new ObjectMapper(), permits, mock(HttpClient.class), probe, mock(VideoMetrics.class));
        workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setTenantId(7L);
        workflow.setTaskId(8L);
        workflow.setStatus("QA");
        workflow.setStage("QA");
        workflow.setOutputAssetId(99L);
        workflow.setOutputStorageKey(KEY);
        workflow.setVideoConcurrencyPermitHeld(true);
        request("720p", "16:9", 5);
        output = new Asset();
        output.setId(99L);
        output.setStatus("PROCESSING");
        output.setStorageKey(KEY);
        when(assets.findById(99L)).thenReturn(Optional.of(output));
        when(assets.findByStorageKey(KEY)).thenReturn(Optional.of(output));
        when(workflows.lock(42L)).thenReturn(Optional.of(workflow));
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
        when(storage.stat(KEY)).thenReturn(Optional.of(new ObjectStorage.StoredObject(100, "video/mp4")));
        when(storage.presignGet(eq(KEY), any())).thenReturn("https://storage.example/output.mp4");
        task = new Task();
        task.setId(8L);
        when(tasks.ownsExecution(task)).thenReturn(true);
    }

    @AfterEach void clearContext() { TenantContext.clear(); }

    @ParameterizedTest
    @CsvSource({
            "1080p, 9:16, 1088, 1920, NONE",
            "1080p, 16:9, 1920, 1088, NONE",
            "720p, 1:1, 720, 724, NONE",
            "480p, 4:3, 640, 480, NONE",
            "480p, 3:4, 480, 640, NONE",
            "720p, 21:9, 1680, 720, NONE",
            "4K, 16:9, 3840, 2160, NONE",
            "720p, auto, 900, 720, NONE",
            "720p, auto, 1280, 736, NONE",
            "720p, auto, 1280, 737, VIDEO_RESOLUTION_MISMATCH",
            "1080p, auto, 1920, 1102, NONE",
            "1080p, auto, 1920, 1103, VIDEO_RESOLUTION_MISMATCH",
            "4K, auto, 4000, 2204, NONE",
            "4K, auto, 4000, 2205, VIDEO_RESOLUTION_MISMATCH",
            "1080p, 9:16, 720, 1280, VIDEO_RESOLUTION_MISMATCH",
            "720p, 16:9, 720, 1280, VIDEO_ORIENTATION_MISMATCH",
            "720p, 9:16, 1280, 720, VIDEO_ORIENTATION_MISMATCH",
            "720p, 16:9, 1440, 720, VIDEO_RATIO_MISMATCH",
            "480p, 1:1, 489, 480, NONE",
            "480p, 1:1, 490, 480, VIDEO_RATIO_MISMATCH"
    })
    void actualDimensionsAreExposedAndOnlyOutOfToleranceOutputsHaveWarnings(String resolution, String ratio,
            int width, int height, String warning) {
        request(resolution, ratio, 5);
        when(probe.probe(KEY, "video/mp4")).thenReturn(new VideoMediaProbe.Metadata(width, height, 5000, "video/mp4"));
        service.completeQa(42L, task);
        var view = service.get(42L);
        assertThat(view.actualWidth()).isEqualTo(width);
        assertThat(view.actualHeight()).isEqualTo(height);
        assertThat(view.actualDurationMs()).isEqualTo(5000);
        assertThat(view.resolution()).isEqualTo(resolution);
        if (warning.equals("NONE")) assertThat(view.qaWarnings()).isEmpty();
        else assertThat(view.qaWarnings()).extracting(VideoDtos.QaWarning::code).containsExactly(warning);
        assertRetainedAndPublished(view);
    }

    @ParameterizedTest
    @CsvSource({
            "5, 4800, false", "5, 5200, false", "5, 4700, false", "5, 5300, false",
            "5, 4699, true", "5, 5301, true", "5, 4300, true", "5, 7000, true",
            "15, 14700, false", "15, 15300, false", "15, 15301, true",
            "30, 29400, false", "30, 30600, false", "30, 29399, true", "30, 30601, true"
    })
    void durationComparisonUsesMillisecondsAndAnInclusiveTolerance(int requestedSeconds, int actualMs, boolean warning) {
        request("720p", "16:9", requestedSeconds);
        when(probe.probe(KEY, "video/mp4")).thenReturn(new VideoMediaProbe.Metadata(1280, 720, actualMs, "video/mp4"));
        service.completeQa(42L, task);
        var view = service.get(42L);
        assertThat(view.durationSeconds()).isEqualTo(requestedSeconds);
        assertThat(view.actualDurationMs()).isEqualTo(actualMs);
        if (warning) assertThat(view.qaWarnings()).extracting(VideoDtos.QaWarning::code).containsExactly("VIDEO_DURATION_MISMATCH");
        else assertThat(view.qaWarnings()).isEmpty();
        assertRetainedAndPublished(view);
    }

    @Test
    void reportsAllIndependentDifferencesInsteadOfStoppingAtTheFirstOne() {
        request("1080p", "16:9", 5);
        when(probe.probe(KEY, "video/mp4")).thenReturn(new VideoMediaProbe.Metadata(720, 1280, 7000, "video/mp4"));
        service.completeQa(42L, task);
        assertThat(service.get(42L).qaWarnings()).extracting(VideoDtos.QaWarning::code)
                .containsExactly("VIDEO_RESOLUTION_MISMATCH", "VIDEO_ORIENTATION_MISMATCH", "VIDEO_DURATION_MISMATCH");
    }

    @Test
    void ffprobeFailureStillInvalidatesAndDeletesAnUnpublishedOutput() {
        when(probe.probe(KEY, "video/mp4")).thenThrow(new IllegalStateException("ffprobe failed"));
        service.completeQa(42L, task);
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(workflow.getErrorCode()).isEqualTo("VIDEO_ASSET_INVALID");
        assertThat(workflow.getOutputPublishedAt()).isNull();
        assertThat(output.getStatus()).isEqualTo("INVALID");
        verify(storage).delete(KEY);
        verify(permits).releaseVideoGeneration(7L);
    }

    @Test
    void aMissingFfprobeExecutableEndsTheWorkflowOnTheFirstAttempt() {
        var realProbe = new VideoMediaProbe(storage, new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(realProbe, "ffprobePath", java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "wuyao-missing-ffprobe-" + java.util.UUID.randomUUID()).toString());
        service = new VideoWorkflowService(workflows, mock(VideoProviderJobRepository.class), assets, mock(AssetService.class),
                storage, tasks, new ObjectMapper(), permits, mock(HttpClient.class), realProbe, mock(VideoMetrics.class));
        task.setAttempts(1);
        task.setMaxAttempts(3);
        task.setPayload(java.util.Map.of("workflowId", 42L));
        assertThatCode(() -> new VideoQaHandler(service).handle(task)).doesNotThrowAnyException();
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(workflow.getErrorCode()).isEqualTo("VIDEO_ASSET_INVALID");
        assertThat(output.getStatus()).isEqualTo("INVALID");
        verify(permits).releaseVideoGeneration(7L);
        verify(storage).delete(KEY);
        verify(tasks, never()).submit(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @Test
    void beforeQaActualFieldsAreUnknownAndNoMismatchIsClaimed() {
        var view = service.get(42L);
        assertThat(view.actualWidth()).isNull();
        assertThat(view.actualHeight()).isNull();
        assertThat(view.actualDurationMs()).isNull();
        assertThat(view.qaWarnings()).isEmpty();
        assertThat(view.outputUrl()).isNull();
    }

    @Test
    void historicalSuccessWithIncompleteMetadataIsExplicitlyUnverifiedAndNeverDeleted() {
        workflow.setStatus("SUCCEEDED");
        output.setStatus("READY");
        var view = service.get(42L);
        assertThat(view.qaWarnings()).extracting(VideoDtos.QaWarning::code).containsExactly("VIDEO_METADATA_UNAVAILABLE");
        assertThat(view.actualWidth()).isNull();
        verify(storage, never()).delete(anyString());
        verifyNoInteractions(probe);
    }

    private void assertRetainedAndPublished(VideoDtos.View view) {
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.progress()).isEqualTo(100);
        assertThat(view.outputUrl()).isEqualTo("https://storage.example/output.mp4");
        assertThat(view.retryable()).isFalse();
        assertThat(output.getStatus()).isEqualTo("READY");
        assertThat(workflow.getOutputPublishedAt()).isNotNull();
        assertThat(workflow.isVideoConcurrencyPermitHeld()).isFalse();
        // Publication protection also applies to outputs carrying warnings.
        service.cancel(42L);
        service.markFailed(42L, "VIDEO_ASSET_INVALID", "late error");
        assertThat(workflow.getStatus()).isEqualTo("SUCCEEDED");
        verify(storage, never()).delete(anyString());
        verify(tasks, never()).submit(anyString(), anyString(), anyMap(), anyString(), any());
        verify(permits, times(1)).releaseVideoGeneration(7L);
    }

    private void request(String resolution, String ratio, int seconds) {
        workflow.setResolution(resolution);
        workflow.setRatio(ratio);
        workflow.setDurationSeconds(seconds);
        workflow.setRequest(new VideoDtos.Create("qa-video-42", "旋转", List.of(), null,
                resolution.equals("4K") ? "SEEDANCE_2_0" : "SEEDANCE_2_5", ratio, seconds, resolution));
    }
}
