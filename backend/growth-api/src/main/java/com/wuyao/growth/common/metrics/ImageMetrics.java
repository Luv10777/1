package com.wuyao.growth.common.metrics;

import com.wuyao.growth.common.task.TaskRepository;
import com.wuyao.growth.creative.image.ImageItemRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 图片生成业务指标监控
 */
@Slf4j
@Component
public class ImageMetrics {
    private final MeterRegistry registry;
    private final TaskRepository taskRepo;
    private final ImageItemRepository itemRepo;

    public ImageMetrics(MeterRegistry registry, TaskRepository taskRepo, ImageItemRepository itemRepo) {
        this.registry = registry;
        this.taskRepo = taskRepo;
        this.itemRepo = itemRepo;

        // 队列深度监控
        Gauge.builder("image.queue.pending", taskRepo,
            repo -> repo.countByStatusAndQueue("PENDING", "IMAGE"))
            .description("图片队列中待处理任务数")
            .register(registry);

        Gauge.builder("image.queue.running", taskRepo,
            repo -> repo.countByStatusAndQueue("RUNNING", "IMAGE"))
            .description("图片队列中正在执行的任务数")
            .register(registry);

        log.info("图片业务指标监控已启动");
    }

    /**
     * 记录图片生成耗时
     */
    public void recordGenerationDuration(long durationMs, String status) {
        registry.timer("image.generation.duration",
            "status", status)
            .record(java.time.Duration.ofMillis(durationMs));
    }

    /**
     * 记录轮询次数
     */
    public void recordPollRound(int round, String outcome) {
        registry.counter("image.poll.rounds",
            "outcome", outcome)
            .increment(round);
    }

    public void recordDownload(long durationMs, String outcome) {
        registry.timer("image.download.duration", "status", outcome)
            .record(java.time.Duration.ofMillis(durationMs));
        registry.counter("image.download.total", "status", outcome).increment();
    }
}
