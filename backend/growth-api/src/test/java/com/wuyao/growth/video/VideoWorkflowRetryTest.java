package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.*;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoWorkflowRetryTest {
    private final VideoWorkflowRepository workflows = mock(VideoWorkflowRepository.class);
    private final VideoProviderJobRepository jobs = mock(VideoProviderJobRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TenantRateLimiter limiter = mock(TenantRateLimiter.class);
    private VideoWorkflowService service;
    private VideoWorkflow workflow;
    private VideoProviderJob job;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TenantContext.set(7L);
        service = new VideoWorkflowService(workflows, jobs, assets, mock(AssetService.class), storage, tasks,
                new ObjectMapper(), limiter, mock(HttpClient.class), mock(VideoMediaProbe.class),
                mock(com.wuyao.growth.common.metrics.VideoMetrics.class));
        ReflectionTestUtils.setField(service, "maxDurationSeconds", 7200L);
        ReflectionTestUtils.setField(service, "tenantMaxConcurrent", 2);
        ReflectionTestUtils.setField(service, "globalMaxConcurrent", 20);
        ReflectionTestUtils.setField(service, "concurrencyPermitTtlSeconds", 21600L);
        workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setTenantId(7L);
        workflow.setTaskId(8L);
        workflow.setRequest(new VideoDtos.Create("retry-video-42", "旋转", List.of(), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p"));
        workflow.setModel("SEEDANCE_2_0_MINI");
        workflow.setRatio("auto");
        workflow.setDurationSeconds(5);
        workflow.setResolution("480p");
        workflow.setStatus("FAILED");
        workflow.setErrorCode("VIDEO_IMPORT_FAILED");
        workflow.setProviderJobId("job-42");
        workflow.setProviderStatus("SUCCEEDED");
        job = new VideoProviderJob();
        job.setProviderJobId("job-42");
        job.setStatus("SUCCEEDED");
        when(workflows.lock(42L)).thenReturn(Optional.of(workflow));
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
        when(jobs.findByWorkflowIdAndProviderJobId(42L, "job-42")).thenReturn(Optional.of(job));
        when(tasks.statusForTenant(anyLong())).thenReturn(TaskStatus.PENDING);
        when(limiter.tryAcquireVideoGeneration(7L, 2, 20, 21600L)).thenReturn(true);
        when(tasks.submit(anyString(), anyString(), anyMap(), anyString(), any())).thenAnswer(invocation -> {
            Task task = new Task();
            task.setId(100L);
            task.setType(invocation.getArgument(0));
            task.setQueue(invocation.getArgument(1));
            return task;
        });
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIDEO_TIMEOUT", "VIDEO_POLL_FAILED", "VIDEO_IMPORT_FAILED", "VIDEO_ASSET_MISSING", "VIDEO_ASSET_INVALID", "VIDEO_QA_FAILED"})
    void recoveryReusesTheProviderJobAndGetsANewProcessingWindow(String code) {
        workflow.setErrorCode(code);
        workflow.setCreatedAt(Instant.now().minusSeconds(86400));
        workflow.setPollRound(360);
        workflow.setProviderSubmitStartedAt(Instant.now().minusSeconds(86400));
        workflow.setProviderSubmitBody("{\"prompt\":\"first\"}");
        workflow.setProviderResultUrl("https://expired.example/video");
        assertThat(service.get(42L).retryMayCharge()).isFalse();

        var result = service.retry(42L, 8L, 9L);

        assertThat(result.status()).isEqualTo("GENERATING");
        assertThat(result.taskId()).isEqualTo(100L);
        assertThat(workflow.getProviderJobId()).isEqualTo("job-42");
        assertThat(workflow.getSubmissionGeneration()).isZero();
        assertThat(workflow.getRetryRound()).isEqualTo(1);
        assertThat(workflow.getPollRound()).isZero();
        assertThat(workflow.getCreatedAt()).isBefore(Instant.now().minusSeconds(7200));
        assertThat(workflow.getAttemptStartedAt()).isAfter(Instant.now().minusSeconds(10));
        assertThat(workflow.getProviderSubmitBody()).isEqualTo("{\"prompt\":\"first\"}");
        assertThat(workflow.getProviderResultUrl()).isNull();
        assertThat(workflow.isVideoConcurrencyPermitHeld()).isTrue();
        verify(tasks).submit(eq("VIDEO_POLL"), eq("VIDEO_PROVIDER"), anyMap(), eq("video-poll-42-retry-1-1"), any());
        verify(tasks, never()).submit(eq("VIDEO_SUBMIT"), anyString(), anyMap(), anyString(), any());
        verify(limiter).tryAcquireVideoGeneration(7L, 2, 20, 21600L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "CANCELLED", "QUEUED", "SUBMITTING", "GENERATING", "IMPORTING", "QA"})
    void successfulCanceledAndActiveWorkflowsCannotBeRetried(String status) {
        workflow.setStatus(status);
        assertThatThrownBy(() -> service.retry(42L, 8L, 9L)).isInstanceOf(BizException.class).hasMessageContaining("不允许重试");
        verifyNoInteractions(limiter);
        verify(tasks, never()).submit(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIDEO_PROVIDER_REJECTED", "VIDEO_PROVIDER_POLL_REJECTED", "VIDEO_SUBMIT_REQUEST_INVALID",
            "VIDEO_SUBMIT_REPLAY_UNSAFE", "VIDEO_REFERENCE_URL_INVALID", "VIDEO_REFERENCE_URL_EXPIRED", "VIDEO_IMPORT_REJECTED", "UNKNOWN_FAILURE"})
    void permanentAndUnclassifiedFailuresCannotSpendMoreProviderQuota(String code) {
        workflow.setErrorCode(code);
        assertThat(service.get(42L).retryable()).isFalse();
        assertThatThrownBy(() -> service.retry(42L, 8L, 9L)).isInstanceOf(BizException.class);
        verifyNoInteractions(limiter);
        verify(tasks, never()).submit(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED"})
    void aConfirmedProviderFailureAllowsAnExplicitNewPaidGeneration(String providerStatus) {
        job.setStatus(providerStatus);
        workflow.setProviderStatus(providerStatus);
        workflow.setErrorCode("VIDEO_PROVIDER_GENERATION_FAILED");
        workflow.setProviderSubmitBody("old body");
        workflow.setProviderSubmitStartedAt(Instant.now());
        var before = service.get(42L);
        assertThat(before.retryable()).isTrue();
        assertThat(before.retryMayCharge()).isTrue();
        assertThat(before.retryHint()).contains("可能再次计费");

        var result = service.retry(42L, 8L, 9L);

        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(workflow.getSubmissionGeneration()).isEqualTo(1);
        assertThat(VideoWorkflowService.submissionKey(workflow)).isEqualTo("video-42-submit-generation-1");
        assertThat(workflow.getProviderJobId()).isNull();
        assertThat(workflow.getProviderSubmitBody()).isNull();
        assertThat(workflow.getProviderSubmitStartedAt()).isNull();
        verify(tasks).submit(eq("VIDEO_SUBMIT"), eq("VIDEO_PROVIDER"), anyMap(), eq("video-submit-42-retry-1"), eq(9L));
        verify(jobs, never()).delete(any());
    }

    @Test
    void aMissingDurableProviderFailureRecordIsNotProofOfSafeResubmission() {
        workflow.setErrorCode("VIDEO_PROVIDER_FAILED");
        workflow.setProviderStatus("FAILED");
        when(jobs.findByWorkflowIdAndProviderJobId(42L, "job-42")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retry(42L, 8L, 9L)).isInstanceOf(BizException.class).hasMessageContaining("核对原任务");
        verifyNoInteractions(limiter);
    }

    @Test
    void anUnknownSubmissionOutcomePreservesItsEvidenceAndRequiresReconciliation() {
        workflow.setProviderJobId(null);
        workflow.setErrorCode("VIDEO_SUBMIT_FAILED");
        workflow.setProviderSubmitStartedAt(Instant.now());
        workflow.setProviderSubmitBody("exact original bytes");
        assertThat(service.get(42L).retryHint()).contains("避免重复计费");
        assertThatThrownBy(() -> service.retry(42L, 8L, 9L)).isInstanceOf(BizException.class).hasMessageContaining("提交结果尚未确认");
        assertThat(workflow.getProviderSubmitBody()).isEqualTo("exact original bytes");
        verifyNoInteractions(limiter);
        verify(tasks, never()).submit(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @Test
    void aDefinitelyUnsentFailureUsesTheOriginalSubmissionIdentity() {
        workflow.setProviderJobId(null);
        workflow.setErrorCode("VIDEO_TIMEOUT");
        workflow.setProviderSubmitRequest(Map.of("prompt", "old candidate"));
        service.retry(42L, 8L, 9L);
        assertThat(workflow.getProviderSubmitRequest()).isNull();
        assertThat(workflow.getSubmissionGeneration()).isZero();
        assertThat(VideoWorkflowService.submissionKey(workflow)).isEqualTo("video-42-submit");
        verify(tasks).submit(eq("VIDEO_SUBMIT"), anyString(), anyMap(), eq("video-submit-42-retry-1"), eq(9L));
    }

    @Test
    void interruptedQaKeepsItsCandidateAndDoesNotAcquireASecondPermit() {
        workflow.setStatus("QA");
        workflow.setStage("QA");
        workflow.setVideoConcurrencyPermitHeld(true);
        workflow.setOutputAssetId(99L);
        workflow.setOutputStorageKey("t7/generated-video/42/output.mp4");
        when(tasks.statusForTenant(8L)).thenReturn(TaskStatus.FAILED);
        assertThat(service.get(42L).status()).isEqualTo("INTERRUPTED");
        assertThat(service.get(42L).outputUrl()).isNull();

        var result = service.retry(42L, 8L, 9L);

        assertThat(result.status()).isEqualTo("QA");
        assertThat(workflow.getOutputStorageKey()).isEqualTo("t7/generated-video/42/output.mp4");
        verify(tasks).submit(eq("VIDEO_QA"), eq("MEDIA_CPU"), anyMap(), eq("video-qa-42-retry-1"), eq(9L));
        verifyNoInteractions(limiter);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aStaleTaskIdDoesNotMutateStateAcquireOrEnqueue() {
        assertThat(service.retry(42L, 1L, 9L).taskId()).isEqualTo(8L);
        assertThat(workflow.getRetryRound()).isZero();
        verifyNoInteractions(limiter);
        verify(tasks, never()).submit(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @Test
    void enqueueFailureReleasesTheNewPermitOnRollback() {
        when(tasks.submit(anyString(), anyString(), anyMap(), anyString(), any())).thenThrow(new IllegalStateException("enqueue failed"));
        assertThatThrownBy(() -> service.retry(42L, 8L, 9L)).hasMessage("enqueue failed");
        verify(limiter, never()).releaseVideoGeneration(anyLong());
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(limiter).releaseVideoGeneration(7L);
    }

    @Test
    void retryRefusesToAcquireAPermitOutsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> service.retry(42L, 8L, 9L)).hasMessage("视频并发许可必须在事务中申请");
        verifyNoInteractions(limiter);
    }

    @Test
    void failedCleanupRetainsItsKeysAndCannotPreventRecovery() {
        workflow.setOutputAssetId(99L);
        workflow.setOutputStorageKey("t7/generated-video/42/output.mp4");
        Asset output = new Asset();
        output.setStatus("PENDING");
        when(assets.findById(99L)).thenReturn(Optional.of(output));
        doThrow(new IllegalStateException("storage offline")).when(storage).delete(anyString());

        service.retry(42L, 8L, 9L);

        assertThat(output.getStatus()).isEqualTo("INVALID");
        assertThat(workflow.getOutputAssetId()).isNull();
        assertThat(workflow.getOutputStorageKey()).isNull();
        assertThat(workflow.getPendingCleanupKeys()).containsExactly("t7/generated-video/42/output.mp4");
        assertThat(workflow.getStatus()).isEqualTo("GENERATING");
    }

    @Test
    void anOldAttemptCannotFailAResumedWorkflowOrReleaseItsPermit() {
        service.retry(42L, 8L, 9L);
        Task old = new Task();
        old.setId(8L);
        when(tasks.ownsExecution(old)).thenReturn(true);
        service.markFailed(42L, old, "VIDEO_POLL_FAILED", "late failure");
        assertThat(workflow.getStatus()).isEqualTo("GENERATING");
        assertThat(workflow.isVideoConcurrencyPermitHeld()).isTrue();
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(limiter, never()).releaseVideoGeneration(anyLong());
    }

    @Test
    void terminalCleanupFailureDoesNotBlockPermitReleaseAndSuccessfulOutputsAreProtected() {
        workflow.setStatus("QA");
        workflow.setOutputStorageKey("t7/generated-video/42/output.mp4");
        workflow.setVideoConcurrencyPermitHeld(true);
        doThrow(new IllegalStateException("storage offline")).when(storage).delete(anyString());
        service.markFailed(42L, "VIDEO_ASSET_INVALID", "QA failed");
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(workflow.isVideoConcurrencyPermitHeld()).isFalse();
        assertThat(workflow.getPendingCleanupKeys()).contains("t7/generated-video/42/output.mp4");
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(limiter).releaseVideoGeneration(7L);
        clearInvocations(storage, limiter);
        workflow.setStatus("SUCCEEDED");
        workflow.setOutputStorageKey("delivered.mp4");
        service.cancel(42L);
        service.markFailed(42L, "VIDEO_POLL_FAILED", "late failure");
        verify(storage, never()).delete(anyString());
        verifyNoInteractions(limiter);
        assertThat(workflow.getOutputStorageKey()).isEqualTo("delivered.mp4");
    }

    @Test
    void aStaleImportDeletesOnlyItsUnpublishedObject() {
        service.retry(42L, 8L, 9L);
        Task old = new Task();
        old.setId(8L);
        when(tasks.ownsExecution(old)).thenReturn(false);
        clearInvocations(storage);
        service.saveImportedVideo(42L, old, "t7/generated-video/42/output.mp4", 10, "video/mp4");
        verify(storage).delete("t7/generated-video/42/output.mp4");
        verify(assets, never()).saveAndFlush(any());
    }

    private void complete(int status) {
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) synchronization.afterCommit();
            synchronization.afterCompletion(status);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }
}
