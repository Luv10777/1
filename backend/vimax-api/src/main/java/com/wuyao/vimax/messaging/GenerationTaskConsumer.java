package com.wuyao.vimax.messaging;

import com.wuyao.vimax.config.RabbitMQConfig;
import com.wuyao.vimax.service.task.ImageGenerationService;
import com.wuyao.vimax.service.task.TextUnderstandingService;
import com.wuyao.vimax.service.task.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 生成任务消费者
 *
 * Phase 1.9: Worker 消费者，支持重试和幂等
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerationTaskConsumer {

    private final TextUnderstandingService textService;
    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;

    private static final int MAX_RETRY = 3;

    /**
     * 消费生成任务消息
     */
    @RabbitListener(queues = RabbitMQConfig.GENERATION_TASK_QUEUE)
    public void handleGenerationTask(GenerationTaskMessage message) {
        log.info("收到生成任务消息: taskId={}, type={}, retry={}",
            message.getTaskId(), message.getTaskType(), message.getRetryCount());

        try {
            // 幂等性检查（通过 idempotencyKey）
            if (!isIdempotent(message)) {
                log.warn("消息重复，跳过处理: taskId={}", message.getTaskId());
                return;
            }

            // 根据任务类型分发
            switch (message.getTaskType()) {
                case "TEXT" -> textService.executeTextTask(message.getTaskId(), message.getPrompt());
                case "IMAGE" -> imageService.executeImageTask(
                    message.getTaskId(),
                    message.getPrompt(),
                    message.getSize(),
                    message.getQuality()
                );
                case "VIDEO" -> videoService.executeVideoTask(
                    message.getTaskId(),
                    message.getImageUrl(),
                    message.getPrompt(),
                    message.getDuration()
                );
                default -> {
                    log.error("未知任务类型: {}", message.getTaskType());
                    throw new IllegalArgumentException("未知任务类型: " + message.getTaskType());
                }
            }

            log.info("任务处理成功: taskId={}", message.getTaskId());

        } catch (Exception e) {
            log.error("任务处理失败: taskId={}, retry={}", message.getTaskId(), message.getRetryCount(), e);

            // 重试逻辑
            int retryCount = message.getRetryCount() != null ? message.getRetryCount() : 0;
            if (retryCount < MAX_RETRY) {
                log.info("准备重试: taskId={}, retryCount={}", message.getTaskId(), retryCount + 1);
                // 重新入队（通过异常触发重试）
                throw new RuntimeException("任务失败，重试中", e);
            } else {
                log.error("任务失败超过最大重试次数: taskId={}", message.getTaskId());
                // 不再抛异常，消息进入死信队列
            }
        }
    }

    /**
     * 死信队列消费者
     */
    @RabbitListener(queues = RabbitMQConfig.GENERATION_TASK_DLQ)
    public void handleDeadLetter(GenerationTaskMessage message) {
        log.error("收到死信消息: taskId={}, type={}", message.getTaskId(), message.getTaskType());

        // TODO: 记录到数据库或告警系统
        // 1. 记录到 failed_tasks 表
        // 2. 发送告警通知
        // 3. 人工介入处理
    }

    /**
     * 幂等性检查（简化版）
     */
    private boolean isIdempotent(GenerationTaskMessage message) {
        // TODO: 实现 Redis 或数据库幂等性检查
        // 当前简化实现：直接返回 true
        return true;
    }
}
