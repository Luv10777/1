package com.wuyao.vimax.service;

import com.wuyao.vimax.entity.OutboxEvent;
import com.wuyao.vimax.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 事件发布器
 *
 * 定时轮询 outbox_events 表，发送未发布的事件到 RabbitMQ
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    /**
     * 每秒轮询一次待发布的事件
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents();

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("发现 {} 个待发布事件", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                // 发送到 RabbitMQ
                rabbitTemplate.convertAndSend(
                        "vimax.events",  // exchange
                        event.getRoutingKey(),
                        event.getPayload()
                );

                // 标记为已发布
                event.setPublishedAt(LocalDateTime.now());
                event.setPublishedBy("OutboxPublisher");
                outboxEventRepository.save(event);

                log.info("事件发布成功：eventId={}, type={}", event.getEventId(), event.getEventType());

            } catch (Exception e) {
                // 记录错误并增加重试次数
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(e.getMessage());
                outboxEventRepository.save(event);

                log.error("事件发布失败：eventId={}, retryCount={}, error={}",
                        event.getEventId(), event.getRetryCount(), e.getMessage());

                // 重试次数达到上限后记录警告
                if (event.getRetryCount() >= 5) {
                    log.warn("事件发布失败达到最大重试次数：eventId={}", event.getEventId());
                }
            }
        }
    }

    /**
     * 创建 Outbox 事件
     */
    @Transactional
    public OutboxEvent createEvent(String aggregateType, String aggregateId,
                                   String eventType, String payload, String routingKey) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setRoutingKey(routingKey);

        OutboxEvent saved = outboxEventRepository.save(event);
        log.info("Outbox 事件已创建：eventId={}, type={}", saved.getEventId(), eventType);

        return saved;
    }
}
