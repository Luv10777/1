package com.wuyao.growth.common.metrics;

import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.task.TaskRepository;
import com.wuyao.growth.video.VideoWorkflowObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static com.wuyao.growth.common.metrics.VideoMetrics.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoMetricsTest {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TenantRateLimiter permits = mock(TenantRateLimiter.class);
    private final VideoWorkflowObservations workflows = mock(VideoWorkflowObservations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void gaugesAreLazyAndQueueLabelsAndPermitLimitsAreBounded() {
        new VideoMetrics(registry, tasks, permits, workflows, 20, 2);
        verifyNoInteractions(tasks, permits, workflows);
        when(tasks.countByStatusAndQueue("PENDING", "VIDEO_PROVIDER")).thenReturn(6L);
        when(tasks.countByStatusAndQueue("RUNNING", "MEDIA_CPU")).thenReturn(3L);
        when(permits.getVideoGlobalActiveCount()).thenReturn(7L);
        when(workflows.snapshot()).thenReturn(new VideoWorkflowObservations.Snapshot(Instant.now(), 1, 2));
        assertThat(registry.get("video.queue.pending").tag("queue", "VIDEO_PROVIDER").gauge().value()).isEqualTo(6);
        assertThat(registry.get("video.queue.running").tag("queue", "MEDIA_CPU").gauge().value()).isEqualTo(3);
        assertThat(registry.get("video.workflow.stuck").gauge().value()).isEqualTo(1);
        assertThat(registry.get("video.concurrency.active").gauge().value()).isEqualTo(7);
        assertThat(registry.get("video.concurrency.tenant.max.active").gauge().value()).isEqualTo(2);
        assertThat(registry.get("video.concurrency.limit").tag("scope", "global").gauge().value()).isEqualTo(20);
        assertThat(registry.get("video.concurrency.limit").tag("scope", "tenant").gauge().value()).isEqualTo(2);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getKey().equals("tenant") || tag.getKey().equals("workflow")));
    }

    @Test
    void unavailableDependenciesProduceNaNRatherThanAHealthyZeroOrNegativeCount() {
        new VideoMetrics(registry, tasks, permits, workflows, 20, 2);
        when(tasks.countByStatusAndQueue(anyString(), anyString())).thenThrow(new IllegalStateException("database unavailable"));
        when(permits.getVideoGlobalActiveCount()).thenReturn(-1L);
        when(workflows.snapshot()).thenReturn(new VideoWorkflowObservations.Snapshot(Instant.now(), 0, Double.NaN));
        assertThat(registry.get("video.queue.pending").tag("queue", "VIDEO_PROVIDER").gauge().value()).isNaN();
        assertThat(registry.get("video.concurrency.active").gauge().value()).isNaN();
        assertThat(registry.get("video.concurrency.tenant.max.active").gauge().value()).isNaN();
    }

    @Test
    void pollRoundsAreIndividualStageSamplesAndDurationIsTheTotalInterval() {
        var metrics = new VideoMetrics(registry, tasks, permits, workflows, 20, 2);
        metrics.recordStage(Stage.POLL, Duration.ofMinutes(20), Outcome.SUCCEEDED, 30);
        metrics.recordStage(Stage.POLL, Duration.ofMinutes(40), Outcome.SUCCEEDED, 60);
        var rounds = registry.get("video.poll.rounds").tag("outcome", "succeeded").summary();
        assertThat(rounds.count()).isEqualTo(2);
        assertThat(rounds.totalAmount()).isEqualTo(90);
        assertThat(rounds.max()).isEqualTo(60);
        var duration = registry.get("video.poll.duration").tag("outcome", "succeeded").timer();
        assertThat(duration.count()).isEqualTo(2);
        assertThat(duration.totalTime(TimeUnit.MINUTES)).isEqualTo(60);
    }
}
