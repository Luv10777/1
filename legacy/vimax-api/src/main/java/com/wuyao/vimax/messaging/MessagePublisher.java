package com.wuyao.vimax.messaging;

import com.wuyao.vimax.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * 消息发布服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发布生成任务消息
     */
    public void publishGenerationTask(GenerationTaskMessage message) {
        log.info("发布生成任务消息: taskId={}, type={}", message.getTaskId(), message.getTaskType());

        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.GENERATION_TASK_EXCHANGE,
                RabbitMQConfig.GENERATION_TASK_ROUTING_KEY,
                message
            );

            log.info("消息发布成功: taskId={}", message.getTaskId());

        } catch (Exception e) {
            log.error("消息发布失败: taskId={}", message.getTaskId(), e);
            throw new RuntimeException("消息发布失败", e);
        }
    }
}
