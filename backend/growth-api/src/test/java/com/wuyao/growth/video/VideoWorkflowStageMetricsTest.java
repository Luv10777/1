package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.*;
import com.wuyao.growth.common.metrics.VideoMetrics;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.*;
import com.wuyao.growth.common.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoWorkflowStageMetricsTest {
    private final VideoWorkflowRepository workflows = mock(VideoWorkflowRepository.class);
    private final VideoProviderJobRepository jobs = mock(VideoProviderJobRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final TaskService tasks = mock(TaskService.class);
    private final VideoMediaProbe probe = mock(VideoMediaProbe.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private VideoWorkflowService service;
    private VideoWorkflow workflow;
    private Task task;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        var permits = mock(TenantRateLimiter.class);
        var metrics = new VideoMetrics(meters, mock(TaskRepository.class), permits, mock(VideoWorkflowObservations.class), 20, 2);
        service = new VideoWorkflowService(workflows, jobs, assets, mock(AssetService.class), storage, tasks,
                new ObjectMapper(), permits, mock(HttpClient.class), probe, metrics);
        ReflectionTestUtils.setField(service, "maxDurationSeconds", 7200L);
        ReflectionTestUtils.setField(service, "maxPolls", 360);
        workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setTenantId(7L);
        workflow.setTaskId(8L);
        workflow.setStatus("GENERATING");
        workflow.setStage("POLL");
        workflow.setStageStartedAt(Instant.now().minusSeconds(600));
        workflow.setProviderJobId("job-42");
        workflow.setVideoConcurrencyPermitHeld(true);
        workflow.setRequest(new VideoDtos.Create("metrics-video-42", "旋转", List.of(), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p"));
        workflow.setResolution("480p");
        workflow.setRatio("auto");
        workflow.setDurationSeconds(5);
        task = new Task();
        task.setId(8L);
        task.setPayload(Map.of("workflowId", 42L));
        when(workflows.lock(42L)).thenReturn(Optional.of(workflow));
        when(tasks.ownsExecution(task)).thenReturn(true);
        when(tasks.statusForTenant(anyLong())).thenReturn(TaskStatus.RUNNING);
        Task nextTask = new Task();
        nextTask.setId(9L);
        when(tasks.submit(anyString(), anyString(), anyMap(), anyString(), any())).thenReturn(nextTask);
        var job = new VideoProviderJob();
        job.setStatus("RUNNING");
        when(jobs.findByWorkflowIdAndProviderJobId(42L, "job-42")).thenReturn(Optional.of(job));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        meters.close();
    }

    @Test
    void repeatedPollsKeepTheStartTimeAndRecordOneTotalDurationAndRoundSampleOnCompletion() {
        Instant started = workflow.getStageStartedAt();
        assertThat(service.beginPoll(42L, task)).isTrue();
        service.savePoll(42L, task, new VideoProviderGateway.PollResult("RUNNING", null, null));
        assertThat(workflow.getStageStartedAt()).isEqualTo(started);
        assertThat(timer("poll", "succeeded").count()).isZero();
        task.setId(9L);
        service.savePoll(42L, task, new VideoProviderGateway.PollResult("SUCCEEDED", "https://cdn.example/video.mp4", null));
        assertThat(workflow.getStage()).isEqualTo("IMPORT");
        assertThat(timer("poll", "succeeded").count()).isEqualTo(1);
        assertThat(timer("poll", "succeeded").totalTime(TimeUnit.SECONDS)).isBetween(599.0, 605.0);
        assertThat(meters.get("video.poll.rounds").tag("outcome", "succeeded").summary().count()).isEqualTo(1);
        assertThat(meters.get("video.poll.rounds").tag("outcome", "succeeded").summary().totalAmount()).isEqualTo(2);
    }

    @Test
    void rolledBackTransitionsDoNotEmitDurationOrRoundSamples() {
        beginTransaction();
        service.savePoll(42L, task, new VideoProviderGateway.PollResult("SUCCEEDED", "https://cdn.example/video.mp4", null));
        assertThat(timer("poll", "succeeded").count()).isZero();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(timer("poll", "succeeded").count()).isZero();
        assertThat(meters.get("video.poll.rounds").tag("outcome", "succeeded").summary().count()).isZero();
    }

    @Test
    void committedSubmissionIsTimedAndAConfirmedProviderFailureIsAFailedSubmitStage() {
        workflow.setStage("SUBMIT");
        workflow.setStatus("SUBMITTING");
        beginTransaction();
        service.saveSubmission(42L, task, new VideoProviderGateway.SubmitResult("onlyrouter", "job-42", "FAILED"));
        assertThat(timer("submit", "failed").count()).isZero();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(timer("submit", "failed").count()).isEqualTo(1);
        assertThat(timer("submit", "succeeded").count()).isZero();
    }

    @Test
    void aQaHandlerNormalReturnAfterInvalidMediaIsRecordedAsFailure() {
        prepareQa();
        when(probe.probe(anyString(), anyString())).thenThrow(new IllegalStateException("invalid video"));
        assertThat(new VideoQaHandler(service).handle(task)).containsEntry("status", "PASSED");
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(timer("qa", "failed").count()).isEqualTo(1);
        assertThat(timer("qa", "succeeded").count()).isZero();
        service.completeQa(42L, task);
        assertThat(timer("qa", "failed").count()).isEqualTo(1);
    }

    @Test
    void successfulQaIsRecordedOnlyOnceAndStaleExecutionsAreNotSuccesses() {
        prepareQa();
        when(probe.probe(anyString(), anyString())).thenReturn(new VideoMediaProbe.Metadata(640, 480, 5000, "video/mp4"));
        service.completeQa(42L, task);
        service.completeQa(42L, task);
        assertThat(timer("qa", "succeeded").count()).isEqualTo(1);
        when(tasks.ownsExecution(task)).thenReturn(false);
        service.savePoll(42L, task, new VideoProviderGateway.PollResult("SUCCEEDED", "https://cdn.example/video.mp4", null));
        assertThat(timer("poll", "succeeded").count()).isZero();
    }

    @Test
    void successfulImportCompletesItsStageWhileAutomaticFailuresWaitForATerminalTransition() {
        workflow.setStatus("IMPORTING");
        workflow.setStage("IMPORT");
        service.saveImportedVideo(42L, task, "t7/generated-video/42/output.mp4", 100, "video/mp4");
        assertThat(timer("import", "succeeded").count()).isEqualTo(1);
        assertThat(workflow.getStage()).isEqualTo("QA");
        assertThat(timer("qa", "failed").count()).isZero();
        task.setId(9L);
        service.markFailed(42L, task, "VIDEO_ASSET_MISSING", "missing");
        assertThat(timer("qa", "failed").count()).isEqualTo(1);
    }

    @Test
    void cancelClosesTheActivePollingStageOnceWithItsRoundCount() {
        workflow.setPollRound(12);
        service.cancel(42L);
        service.cancel(42L);
        assertThat(timer("poll", "canceled").count()).isEqualTo(1);
        assertThat(meters.get("video.poll.rounds").tag("outcome", "canceled").summary().totalAmount()).isEqualTo(12);
    }

    @Test
    void manualRecoveryClosesAnInterruptedStageAndStartsANewAgeWindow() {
        when(tasks.statusForTenant(8L)).thenReturn(TaskStatus.FAILED);
        workflow.setPollRound(12);
        var resumed = service.retry(42L, 8L, null);
        assertThat(resumed.status()).isEqualTo("GENERATING");
        assertThat(timer("poll", "interrupted").count()).isEqualTo(1);
        assertThat(meters.get("video.poll.rounds").tag("outcome", "interrupted").summary().totalAmount()).isEqualTo(12);
        assertThat(workflow.getStageStartedAt()).isEqualTo(workflow.getAttemptStartedAt());
        assertThat(workflow.getStageStartedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    private void prepareQa() {
        workflow.setStatus("QA");
        workflow.setStage("QA");
        workflow.setOutputAssetId(99L);
        workflow.setOutputStorageKey("t7/generated-video/42/output.mp4");
        var asset = new Asset();
        asset.setId(99L);
        asset.setStatus("PROCESSING");
        when(assets.findById(99L)).thenReturn(Optional.of(asset));
        when(storage.stat(anyString())).thenReturn(Optional.of(new ObjectStorage.StoredObject(100, "video/mp4")));
    }

    private io.micrometer.core.instrument.Timer timer(String stage, String outcome) {
        return meters.get("video." + stage + ".duration").tag("outcome", outcome).timer();
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
