package com.wuyao.growth.common.metrics;

import com.wuyao.growth.common.ratelimit.TenantRateLimiter;
import com.wuyao.growth.common.task.TaskRepository;
import com.wuyao.growth.video.VideoWorkflowObservations;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.function.DoubleSupplier;

@Component
@Slf4j
public class VideoMetrics {
    public enum Stage { SUBMIT, POLL, IMPORT, QA }
    public enum Outcome { SUCCEEDED, FAILED, CANCELED, INTERRUPTED }
    private final EnumMap<Stage, EnumMap<Outcome, Timer>> durations = new EnumMap<>(Stage.class);
    private final EnumMap<Outcome, DistributionSummary> pollRounds = new EnumMap<>(Outcome.class);

    public VideoMetrics(MeterRegistry registry, TaskRepository tasks, TenantRateLimiter permits,
            VideoWorkflowObservations workflows,
            @Value("${growth.video.global-max-concurrent:20}") int globalLimit,
            @Value("${growth.video.tenant-max-concurrent:2}") int tenantLimit) {
        for (String queue : new String[]{"VIDEO_PROVIDER", "MEDIA_CPU"}) {
            Gauge.builder("video.queue.pending", () -> observe(() -> tasks.countByStatusAndQueue("PENDING", queue)))
                    .tag("queue", queue).description("Pending video tasks, including delayed polls").register(registry);
            Gauge.builder("video.queue.running", () -> observe(() -> tasks.countByStatusAndQueue("RUNNING", queue)))
                    .tag("queue", queue).description("Running video tasks").register(registry);
        }
        Gauge.builder("video.workflow.stuck", () -> observe(() -> workflows.snapshot().stuckWorkflows()))
                .description("Active workflows exceeding the current stage age limit").register(registry);
        Gauge.builder("video.concurrency.active", () -> observe(() -> permits.getVideoGlobalActiveCount()))
                .description("Global video permits currently held in Redis").register(registry);
        Gauge.builder("video.concurrency.tenant.max.active", () -> observe(() -> workflows.snapshot().maxTenantActive()))
                .description("Largest current Redis permit count among tenants, without tenant labels").register(registry);
        Gauge.builder("video.concurrency.limit", () -> globalLimit).tag("scope", "global").register(registry);
        Gauge.builder("video.concurrency.limit", () -> tenantLimit).tag("scope", "tenant").register(registry);

        for (Stage stage : Stage.values()) {
            var outcomes = new EnumMap<Outcome, Timer>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                outcomes.put(outcome, Timer.builder("video." + label(stage) + ".duration")
                        .tag("outcome", label(outcome))
                        .description("Committed workflow stage elapsed time, including queue waits and retries")
                        .publishPercentileHistogram()
                        .maximumExpectedValue(Duration.ofHours(6))
                        .serviceLevelObjectives(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5),
                                Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(2))
                        .register(registry));
            }
            durations.put(stage, outcomes);
        }
        for (Outcome outcome : Outcome.values()) {
            pollRounds.put(outcome, DistributionSummary.builder("video.poll.rounds")
                    .tag("outcome", label(outcome)).baseUnit("rounds")
                    .description("Completed polling stage round distribution; one sample per stage")
                    .publishPercentileHistogram().serviceLevelObjectives(1, 5, 10, 30, 60, 120, 240, 360)
                    .register(registry));
        }
    }

    public void recordStage(Stage stage, Duration elapsed, Outcome outcome, int rounds) {
        try {
            durations.get(stage).get(outcome).record(elapsed.isNegative() ? Duration.ZERO : elapsed);
            if (stage == Stage.POLL) pollRounds.get(outcome).record(Math.max(0, rounds));
        } catch (RuntimeException e) {
            // Observability must not fail a workflow after its database transaction has committed.
            log.warn("视频阶段指标记录失败: stage={} outcome={}", stage, outcome, e);
        }
    }

    private static String label(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }

    private static double observe(DoubleSupplier query) {
        try {
            double count = query.getAsDouble();
            // TenantRateLimiter uses -1 on Redis failure; this is missing data, not occupancy.
            return count < 0 ? Double.NaN : count;
        } catch (RuntimeException e) {
            log.warn("视频 Gauge 读取失败，返回 NaN", e);
            return Double.NaN;
        }
    }
}
