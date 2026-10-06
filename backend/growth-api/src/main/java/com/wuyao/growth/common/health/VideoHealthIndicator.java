package com.wuyao.growth.common.health;

import com.wuyao.growth.common.task.TaskRepository;
import com.wuyao.growth.video.VideoProviderGateway;
import com.wuyao.growth.video.VideoWorkflowObservations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class VideoHealthIndicator implements HealthIndicator {
    private final TaskRepository tasks;
    private final VideoProviderGateway provider;
    private final VideoWorkflowObservations workflows;
    private final long providerPendingThreshold;
    private final long mediaPendingThreshold;
    private final long stuckThreshold;

    public VideoHealthIndicator(TaskRepository tasks, VideoProviderGateway provider, VideoWorkflowObservations workflows,
            @Value("${growth.video.health.provider-pending-threshold:100}") long providerPendingThreshold,
            @Value("${growth.video.health.media-pending-threshold:20}") long mediaPendingThreshold,
            @Value("${growth.video.health.stuck-workflows-threshold:0}") long stuckThreshold) {
        if (providerPendingThreshold < 0 || mediaPendingThreshold < 0 || stuckThreshold < 0) {
            throw new IllegalArgumentException("视频健康检查阈值不能为负数");
        }
        this.tasks = tasks;
        this.provider = provider;
        this.workflows = workflows;
        this.providerPendingThreshold = providerPendingThreshold;
        this.mediaPendingThreshold = mediaPendingThreshold;
        this.stuckThreshold = stuckThreshold;
    }

    @Override
    public Health health() {
        var builder = Health.up();
        try {
            var queues = new LinkedHashMap<String, Object>();
            var reasons = new ArrayList<String>();
            for (String queue : new String[]{"VIDEO_PROVIDER", "MEDIA_CPU"}) {
                long pending = tasks.countByStatusAndQueue("PENDING", queue);
                long running = tasks.countByStatusAndQueue("RUNNING", queue);
                long threshold = "VIDEO_PROVIDER".equals(queue) ? providerPendingThreshold : mediaPendingThreshold;
                queues.put(queue, Map.of("pending", pending, "running", running, "pending_threshold", threshold));
                if (pending > threshold) reasons.add(queue + "_BACKLOG");
            }
            builder.withDetail("queues", queues);
            boolean configured = provider.configured();
            builder.withDetail("video_provider_configured", configured);
            if (!configured) reasons.add("PROVIDER_UNCONFIGURED");
            var observation = workflows.snapshot();
            builder.withDetail("stuck_workflows", observation.stuckWorkflows())
                    .withDetail("stuck_after_seconds", workflows.stuckAfterSeconds())
                    .withDetail("stuck_threshold", stuckThreshold)
                    .withDetail("stuck_states", VideoWorkflowObservations.ACTIVE_STATES)
                    .withDetail("observed_at", observation.observedAt());
            if (observation.stuckWorkflows() > stuckThreshold) reasons.add("STUCK_WORKFLOWS");
            if (!reasons.isEmpty()) builder.down().withDetail("reasons", reasons);
            return builder.build();
        } catch (RuntimeException e) {
            // Avoid exposing SQL, connection strings or credentials in health details.
            return builder.down().withDetail("error", "视频健康检查无法读取观测数据")
                    .withDetail("error_type", e.getClass().getSimpleName()).build();
        }
    }
}
