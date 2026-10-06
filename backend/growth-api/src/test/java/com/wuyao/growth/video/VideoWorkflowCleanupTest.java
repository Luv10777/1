package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.*;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoWorkflowCleanupTest {
    private static final String KEY = "t7/generated-video/42/output.mp4";
    private static final String IMPORT_KEY = "t7/generated-video/42/import-00000000-0000-0000-0000-000000000001/output.mp4";
    private final VideoWorkflowRepository workflows = mock(VideoWorkflowRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TenantRateLimiter limiter = mock(TenantRateLimiter.class);
    private final VideoMediaProbe probe = mock(VideoMediaProbe.class);
    private VideoWorkflowService service;
    private VideoWorkflow workflow;
    private Asset asset;
    private Task task;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        service = new VideoWorkflowService(workflows, mock(VideoProviderJobRepository.class), assets, mock(AssetService.class),
                storage, tasks, new ObjectMapper(), limiter, mock(HttpClient.class), probe,
                mock(com.wuyao.growth.common.metrics.VideoMetrics.class));
        workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setTenantId(7L);
        workflow.setTaskId(8L);
        workflow.setStatus("QA");
        workflow.setStage("QA");
        workflow.setOutputAssetId(99L);
        workflow.setOutputStorageKey(KEY);
        workflow.setVideoConcurrencyPermitHeld(true);
        workflow.setRequest(new VideoDtos.Create("cleanup-video-42", "旋转", List.of(), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p"));
        workflow.setResolution("480p");
        workflow.setRatio("auto");
        workflow.setDurationSeconds(5);
        asset = new Asset();
        asset.setId(99L);
        asset.setTenantId(7L);
        asset.setStatus("PROCESSING");
        asset.setStorageKey(KEY);
        when(assets.findById(99L)).thenReturn(Optional.of(asset));
        when(assets.findByStorageKey(KEY)).thenReturn(Optional.of(asset));
        when(workflows.lock(42L)).thenReturn(Optional.of(workflow));
        when(storage.stat(KEY)).thenReturn(Optional.of(new ObjectStorage.StoredObject(100, "video/mp4")));
        task = new Task();
        task.setId(8L);
        when(tasks.ownsExecution(task)).thenReturn(true);
        when(tasks.statusForTenant(anyLong())).thenReturn(TaskStatus.PENDING);
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void qaFailureDeletesTheObjectKeepsAnInvalidRecordAndReleasesThePermit() {
        when(probe.probe(KEY, "video/mp4")).thenThrow(new IllegalStateException("invalid media"));
        service.completeQa(42L, task);
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(asset.getStatus()).isEqualTo("INVALID");
        assertThat(workflow.getOutputStorageKey()).isNull();
        assertThat(workflow.getPendingCleanupKeys()).isEmpty();
        verify(storage).delete(KEY);
        verify(limiter).releaseVideoGeneration(7L);
        verify(assets, never()).delete(any());
    }

    @Test
    void missingAssetRecordStillCleansTheUnpublishedOrphanObject() {
        when(assets.findById(99L)).thenReturn(Optional.empty());
        when(assets.findByStorageKey(KEY)).thenReturn(Optional.empty());
        service.completeQa(42L, task);
        assertThat(workflow.getErrorCode()).isEqualTo("VIDEO_ASSET_MISSING");
        verify(storage).delete(KEY);
        verifyNoInteractions(probe);
    }

    @Test
    void aMissingObjectKeepsItsRecordAsInvalidInsteadOfDeletingDatabaseHistory() {
        when(storage.stat(KEY)).thenReturn(Optional.empty());
        service.completeQa(42L, task);
        assertThat(workflow.getErrorCode()).isEqualTo("VIDEO_ASSET_MISSING");
        assertThat(asset.getStatus()).isEqualTo("INVALID");
        verify(storage).delete(KEY);
        verify(assets, never()).delete(any());
    }

    @Test
    void cancellationDeletesTheCandidateAndReservedUnattachedImportObjects() {
        workflow.setPendingCleanupKeys(List.of(IMPORT_KEY));
        service.cancel(42L);
        assertThat(workflow.getStatus()).isEqualTo("CANCELLED");
        assertThat(asset.getStatus()).isEqualTo("INVALID");
        verify(storage).delete(KEY);
        verify(storage).delete(IMPORT_KEY);
        verify(limiter).releaseVideoGeneration(7L);
    }

    @Test
    void cleanupFailureDoesNotReplaceTheQaErrorOrBlockPermitReleaseAndRetainsEveryKey() {
        workflow.setPendingCleanupKeys(List.of(IMPORT_KEY));
        when(probe.probe(KEY, "video/mp4")).thenThrow(new IllegalStateException("invalid media"));
        doThrow(new IllegalStateException("MinIO unavailable")).when(storage).delete(anyString());
        service.completeQa(42L, task);
        assertThat(workflow.getErrorCode()).isEqualTo("VIDEO_ASSET_INVALID");
        assertThat(workflow.getPendingCleanupKeys()).containsExactlyInAnyOrder(KEY, IMPORT_KEY);
        assertThat(workflow.isVideoConcurrencyPermitHeld()).isFalse();
        verify(limiter).releaseVideoGeneration(7L);
        // A later request on a terminal workflow retries deletion without releasing a second permit.
        doNothing().when(storage).delete(anyString());
        service.cancel(42L);
        assertThat(workflow.getPendingCleanupKeys()).isEmpty();
        verify(limiter, times(1)).releaseVideoGeneration(7L);
    }

    @Test
    void cancellationStillFinishesWhenDeletionFails() {
        doThrow(new IllegalStateException("MinIO unavailable")).when(storage).delete(anyString());
        assertThat(service.cancel(42L).status()).isEqualTo("CANCELLED");
        assertThat(workflow.getPendingCleanupKeys()).contains(KEY);
        verify(limiter).releaseVideoGeneration(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELLED", "QA", "IMPORTING"})
    void publicationSurvivesLaterStateChangesAndBlocksEveryCleanupEntry(String state) {
        workflow.setOutputPublishedAt(Instant.now());
        workflow.setStatus(state);
        asset.setStatus("READY");
        service.cancel(42L);
        service.markFailed(42L, "VIDEO_IMPORT_FAILED", "late error");
        service.cleanupImportObject(42L, KEY);
        service.saveImportedVideo(42L, task, KEY, 100, "video/mp4");
        service.completeQa(42L, task);
        assertThat(workflow.getOutputStorageKey()).isEqualTo(KEY);
        assertThat(asset.getStatus()).isEqualTo("READY");
        verify(storage, never()).delete(anyString());
        verify(assets, never()).saveAndFlush(any());
        assertThatThrownBy(() -> service.retry(42L, 8L, 1L)).hasMessageContaining("已通过校验并交付");
    }

    @Test
    void legacyReadyAssetReferencesAreProtectedEvenWithoutAPublicationMarker() {
        asset.setStatus("READY");
        workflow.setPendingCleanupKeys(List.of(KEY));
        service.cancel(42L);
        assertThat(asset.getStatus()).isEqualTo("READY");
        assertThat(workflow.getPendingCleanupKeys()).contains(KEY);
        verify(storage, never()).delete(KEY);
    }

    @Test
    void legacyReadyAssetIsNotDowngradedAndDeletedOnAProbeFailure() {
        asset.setStatus("READY");
        when(probe.probe(KEY, "video/mp4")).thenThrow(new IllegalStateException("probe unavailable"));
        service.completeQa(42L, task);
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(asset.getStatus()).isEqualTo("READY");
        assertThat(workflow.getPendingCleanupKeys()).contains(KEY);
        verify(storage, never()).delete(KEY);
    }

    @Test
    void legacyCompletedProgressProtectsTheObjectEvenWhenTheStatusAndAssetRecordWereLost() {
        workflow.setStatus("FAILED");
        workflow.setProgress(100);
        when(assets.findById(99L)).thenReturn(Optional.empty());
        when(assets.findByStorageKey(KEY)).thenReturn(Optional.empty());
        service.cancel(42L);
        service.markFailed(42L, "VIDEO_IMPORT_FAILED", "late failure");
        service.cleanupImportObject(42L, KEY);
        assertThat(workflow.getOutputStorageKey()).isEqualTo(KEY);
        assertThatThrownBy(() -> service.retry(42L, 8L, 1L)).hasMessageContaining("已通过校验并交付");
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aLateImporterCannotDeleteTheCurrentQaCandidateBeforePublication() {
        service.cleanupImportObject(42L, KEY);
        assertThat(workflow.getOutputStorageKey()).isEqualTo(KEY);
        assertThat(asset.getStatus()).isEqualTo("PROCESSING");
        verify(storage, never()).delete(anyString());
    }

    @Test
    void pendingKeysFromAnotherTenantOrWorkflowAreNeverDeleted() {
        workflow.setPendingCleanupKeys(List.of("t8/generated-video/42/output.mp4", "t7/generated-video/43/output.mp4"));
        service.cancel(42L);
        verify(storage, never()).delete("t8/generated-video/42/output.mp4");
        verify(storage, never()).delete("t7/generated-video/43/output.mp4");
        assertThat(workflow.getPendingCleanupKeys()).hasSize(2);
    }

    @Test
    void aStaleDownloadAfterCancellationDeletesItsOwnKeyAndRetainsDeletionFailure() {
        service.cancel(42L);
        clearInvocations(storage);
        doThrow(new IllegalStateException("MinIO unavailable")).when(storage).delete(IMPORT_KEY);
        service.cleanupImportObject(42L, IMPORT_KEY);
        assertThat(workflow.getPendingCleanupKeys()).contains(IMPORT_KEY);
        doNothing().when(storage).delete(IMPORT_KEY);
        service.cleanupImportObject(42L, IMPORT_KEY);
        assertThat(workflow.getPendingCleanupKeys()).doesNotContain(IMPORT_KEY);
    }

    @Test
    void reserveCommitsDistinctKeysBeforeDownloadAndDoesNotReserveForStaleExecutions() {
        workflow.setStatus("IMPORTING");
        String first = service.reserveImportKey(42L, task).orElseThrow();
        String second = service.reserveImportKey(42L, task).orElseThrow();
        assertThat(first).isNotEqualTo(second).startsWith("t7/generated-video/42/import-").endsWith("/output.mp4");
        assertThat(workflow.getPendingCleanupKeys()).containsExactly(first, second);
        verify(storage, never()).put(anyString(), any(java.io.InputStream.class), anyLong(), anyString());
        when(tasks.ownsExecution(task)).thenReturn(false);
        assertThat(service.reserveImportKey(42L, task)).isEmpty();
    }

    @Test
    void importingTheSamePublishedKeyCannotOverwriteOrDeleteIt() {
        asset.setStatus("READY");
        assertThatThrownBy(() -> service.importUrl(workflow, KEY)).isInstanceOf(VideoImportException.class)
                .hasMessage("保存供应商视频失败：视频对象不可覆盖");
        verify(storage, never()).delete(anyString());
        verify(storage, never()).put(anyString(), any(java.io.InputStream.class), anyLong(), anyString());
    }
}
