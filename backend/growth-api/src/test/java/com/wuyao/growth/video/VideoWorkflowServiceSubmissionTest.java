package com.wuyao.growth.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.asset.AssetRepository;
import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.asset.VideoMediaProbe;
import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.storage.ObjectStorage;
import com.wuyao.growth.common.task.NonRetryableTaskException;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskService;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoWorkflowServiceSubmissionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final VideoWorkflowRepository workflows = mock(VideoWorkflowRepository.class);
    private final TaskService tasks = mock(TaskService.class);
    private final VideoProviderGateway provider = mock(VideoProviderGateway.class);
    private VideoWorkflowService service;
    private VideoWorkflow workflow;
    private Task task;

    @BeforeEach
    void setUp() {
        service = new VideoWorkflowService(workflows, mock(VideoProviderJobRepository.class),
                mock(AssetRepository.class), mock(AssetService.class), mock(ObjectStorage.class), tasks,
                json, mock(TenantRateLimiter.class), mock(HttpClient.class), mock(VideoMediaProbe.class),
                mock(com.wuyao.growth.common.metrics.VideoMetrics.class));
        ReflectionTestUtils.setField(service, "maxDurationSeconds", 7200L);
        workflow = new VideoWorkflow();
        workflow.setId(42L);
        workflow.setTaskId(7L);
        workflow.setRequest(new VideoDtos.Create("request-42", "旋转", List.of(9L), null,
                "SEEDANCE_2_0_MINI", "auto", 5, "480p"));
        task = new Task();
        task.setId(7L);
        task.setAttempts(1);
        task.setMaxAttempts(3);
        task.setPayload(Map.of("workflowId", 42L));
        when(tasks.ownsExecution(task)).thenReturn(true);
        when(workflows.lock(42L)).thenReturn(Optional.of(workflow));
        when(workflows.findById(42L)).thenReturn(Optional.of(workflow));
    }

    @Test
    void anUnsentPersistedExpiredRequestIsReplacedBeforeDispatchIsRecorded() throws Exception {
        workflow.setProviderSubmitRequest(reference("IMAGE", signedUrl(Instant.now().minusSeconds(7200), 3600)));
        Map<String, Object> fresh = reference("IMAGE", signedUrl(Instant.now(), 21600));

        String body = service.prepareSubmissionRequest(42L, task, fresh).orElseThrow();

        assertThat(workflow.getProviderSubmitStartedAt()).isNotNull();
        assertThat(workflow.getProviderSubmitRequest()).isEqualTo(fresh);
        assertThat(workflow.getProviderSubmitBody()).isEqualTo(body).isEqualTo(json.writeValueAsString(fresh));
    }

    @Test
    void retriesReturnIdenticalWireBytesEvenIfJsonbOrderOrTheCandidateChanges() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("resolution", "480p");
        first.putAll(reference("VIDEO", signedUrl(Instant.now(), 21600)));
        first.put("model", "seedance");
        String body = service.prepareSubmissionRequest(42L, task, first).orElseThrow();
        Instant startedAt = workflow.getProviderSubmitStartedAt();
        // PostgreSQL JSONB may load keys in a different order on a later attempt.
        workflow.setProviderSubmitRequest(Map.of("model", "seedance", "resolution", "480p",
                "input_reference", first.get("input_reference")));

        String replay = service.prepareSubmissionRequest(42L, task, Map.of("prompt", "replacement")).orElseThrow();

        assertThat(replay).isEqualTo(body);
        assertThat(workflow.getProviderSubmitBody()).isEqualTo(body);
        assertThat(workflow.getProviderSubmitStartedAt()).isEqualTo(startedAt);
    }

    @ParameterizedTest
    @CsvSource({"IMAGE,-7200,3600", "VIDEO,-7200,3600", "IMAGE,0,120", "VIDEO,0,120"})
    void expiredAndNearlyExpiredDispatchedReferencesFailClearlyWithoutCallingTheProvider(
            String type, long signingOffset, long ttl) throws Exception {
        Map<String, Object> persisted = reference(type, signedUrl(Instant.now().plusSeconds(signingOffset), ttl));
        freeze(persisted);
        String originalBody = workflow.getProviderSubmitBody();
        Instant originalStartedAt = workflow.getProviderSubmitStartedAt();

        assertThatThrownBy(() -> new VideoSubmitHandler(service, provider, workflows).handle(task))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("VIDEO_REFERENCE_URL_EXPIRED"))
                .hasMessageContaining("已过期或即将过期").hasMessageContaining("核对供应商")
                .hasMessageContaining("确认未创建后");

        verifyNoInteractions(provider);
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(workflow.getErrorCode()).isEqualTo("VIDEO_REFERENCE_URL_EXPIRED");
        assertThat(workflow.getProviderSubmitBody()).isEqualTo(originalBody);
        assertThat(workflow.getProviderSubmitStartedAt()).isEqualTo(originalStartedAt);
    }

    @Test
    void theSafetyWindowAlsoCoversAnIncreasedProviderTimeout() throws Exception {
        ReflectionTestUtils.setField(service, "providerTimeoutSeconds", 600L);
        freeze(reference("VIDEO", signedUrl(Instant.now(), 500)));

        assertThatThrownBy(() -> service.prepareSubmissionRequest(42L, task, Map.of()))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("VIDEO_REFERENCE_URL_EXPIRED"));
    }

    @Test
    void legacyPossiblyDispatchedJsonbIsNeverReserializedAndReplayed() {
        workflow.setProviderSubmitRequest(reference("IMAGE", signedUrl(Instant.now(), 21600)));
        workflow.setProviderSubmitStartedAt(Instant.now().minusSeconds(30));

        assertThatThrownBy(() -> new VideoSubmitHandler(service, provider, workflows).handle(task))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("VIDEO_SUBMIT_REPLAY_UNSAFE"))
                .hasMessageContaining("历史视频提交缺少原始请求").hasMessageContaining("核对供应商");

        verifyNoInteractions(provider);
        assertThat(workflow.getProviderSubmitBody()).isNull();
    }

    @Test
    void malformedReferenceExpiryIsRejectedBeforeDispatch() {
        assertThatThrownBy(() -> service.prepareSubmissionRequest(42L, task,
                reference("IMAGE", "https://storage.example/image?X-Amz-Date=bad&X-Amz-Expires=3600")))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("VIDEO_REFERENCE_URL_INVALID"))
                .hasMessageContaining("尚未向供应商提交");
        assertThat(workflow.getProviderSubmitStartedAt()).isNull();
        assertThat(workflow.getProviderSubmitBody()).isNull();
    }

    @Test
    void aStaleLeaseCannotRecordDispatch() {
        when(tasks.ownsExecution(task)).thenReturn(false);
        assertThat(service.prepareSubmissionRequest(42L, task, Map.of("prompt", "旋转"))).isEmpty();
        verify(workflows, never()).lock(anyLong());
        assertThat(workflow.getProviderSubmitStartedAt()).isNull();
    }

    @Test
    void aCanceledWorkflowCannotRecordDispatch() {
        workflow.setStatus("CANCELLED");
        assertThat(service.prepareSubmissionRequest(42L, task, Map.of("prompt", "旋转"))).isEmpty();
        assertThat(workflow.getProviderSubmitStartedAt()).isNull();
    }

    @Test
    void aWorkflowThatExpiresDuringRequestPreparationCannotDispatch() {
        workflow.setCreatedAt(Instant.now().minusSeconds(7201));
        assertThat(service.prepareSubmissionRequest(42L, task, Map.of("prompt", "旋转"))).isEmpty();
        assertThat(workflow.getStatus()).isEqualTo("FAILED");
        assertThat(workflow.getErrorCode()).isEqualTo("VIDEO_TIMEOUT");
        assertThat(workflow.getProviderSubmitStartedAt()).isNull();
    }

    private void freeze(Map<String, Object> body) throws Exception {
        workflow.setProviderSubmitRequest(body);
        workflow.setProviderSubmitBody(json.writeValueAsString(body));
        workflow.setProviderSubmitStartedAt(Instant.now().minusSeconds(30));
    }

    private Map<String, Object> reference(String type, String url) {
        return "VIDEO".equals(type) ? Map.of("input_reference", Map.of("type", "video", "url", url))
                : Map.of("content", List.of(Map.of("type", "image_url", "image_url", Map.of("url", url))));
    }

    private String signedUrl(Instant signingTime, long seconds) {
        String date = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(signingTime);
        return "https://storage.example/reference?X-Amz-Date=" + date + "&X-Amz-Expires=" + seconds;
    }
}
