package com.wuyao.growth.common.health;

import com.wuyao.growth.common.task.TaskRepository;
import com.wuyao.growth.video.VideoProviderGateway;
import com.wuyao.growth.video.VideoWorkflowObservations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoHealthIndicatorTest {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final VideoProviderGateway provider = mock(VideoProviderGateway.class);
    private final VideoWorkflowObservations observations = mock(VideoWorkflowObservations.class);
    private final VideoHealthIndicator health = new VideoHealthIndicator(tasks, provider, observations, 100, 20, 0);

    @BeforeEach
    void setUp() {
        when(provider.configured()).thenReturn(true);
        when(observations.snapshot()).thenReturn(new VideoWorkflowObservations.Snapshot(Instant.now(), 0, 0));
        when(observations.stuckAfterSeconds()).thenReturn(7200L);
    }

    @Test
    void bothQueuesRunningCountsAndConfigurationAppearEvenAtThreshold() {
        when(tasks.countByStatusAndQueue("PENDING", "VIDEO_PROVIDER")).thenReturn(100L);
        when(tasks.countByStatusAndQueue("RUNNING", "VIDEO_PROVIDER")).thenReturn(2L);
        when(tasks.countByStatusAndQueue("PENDING", "MEDIA_CPU")).thenReturn(20L);
        when(tasks.countByStatusAndQueue("RUNNING", "MEDIA_CPU")).thenReturn(3L);
        var result = health.health();
        assertThat(result.getStatus()).isEqualTo(Status.UP);
        var queues = (Map<?, ?>) result.getDetails().get("queues");
        assertThat(queues.get("VIDEO_PROVIDER")).isEqualTo(Map.of("pending", 100L, "running", 2L, "pending_threshold", 100L));
        assertThat(queues.get("MEDIA_CPU")).isEqualTo(Map.of("pending", 20L, "running", 3L, "pending_threshold", 20L));
        assertThat(result.getDetails()).containsEntry("video_provider_configured", true).containsEntry("stuck_workflows", 0L);
    }

    @Test
    void reportsEveryReasonWithoutAnEarlyBacklogReturn() {
        when(tasks.countByStatusAndQueue("PENDING", "VIDEO_PROVIDER")).thenReturn(101L);
        when(tasks.countByStatusAndQueue("PENDING", "MEDIA_CPU")).thenReturn(21L);
        when(provider.configured()).thenReturn(false);
        when(observations.snapshot()).thenReturn(new VideoWorkflowObservations.Snapshot(Instant.now(), 1, 0));
        var result = health.health();
        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails().get("reasons")).isEqualTo(List.of(
                "VIDEO_PROVIDER_BACKLOG", "MEDIA_CPU_BACKLOG", "PROVIDER_UNCONFIGURED", "STUCK_WORKFLOWS"));
    }

    @Test
    void queueDatabaseFailureReturnsDownWithoutLeakingTheExceptionMessage() {
        when(tasks.countByStatusAndQueue(anyString(), anyString())).thenThrow(new IllegalStateException("password=secret"));
        var result = health.health();
        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails().toString()).doesNotContain("secret");
        verifyNoInteractions(observations);
    }

    @Test
    void workflowDatabaseFailureReturnsDownInsteadOfZeroStuckWorkflows() {
        when(observations.snapshot()).thenThrow(new IllegalStateException("connection unavailable"));
        var result = health.health();
        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsKey("error").doesNotContainKey("stuck_workflows");
    }

    @Test
    void acceptsAConfiguredToleranceForStuckWorkflows() {
        when(observations.snapshot()).thenReturn(new VideoWorkflowObservations.Snapshot(Instant.now(), 2, 0));
        assertThat(new VideoHealthIndicator(tasks, provider, observations, 100, 20, 2).health().getStatus()).isEqualTo(Status.UP);
    }
}
