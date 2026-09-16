package com.wuyao.vimax.service;

import com.wuyao.vimax.entity.OutboxEvent;
import com.wuyao.vimax.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 事件服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;

    /**
     * 创建 Outbox 事件
     */
    @Transactional
    public OutboxEvent createEvent(String eventType, String aggregateType,
                                   Long aggregateId, String payload) {
        log.info("创建 Outbox 事件: type={}, aggregateId={}", eventType, aggregateId);

        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(String.valueOf(aggregateId));
        event.setPayload(payload);
        event.setStatus("PENDING");

        OutboxEvent saved = outboxEventRepository.save(event);
        log.info("Outbox 事件已创建: eventId={}", saved.getId());

        return saved;
    }

    /**
     * 查询待发布事件
     */
    public List<OutboxEvent> getPendingEvents() {
        return outboxEventRepository.findPendingEvents();
    }

    /**
     * 标记事件为已发布
     */
    @Transactional
    public void markAsPublished(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在: " + eventId));

        event.setStatus("PUBLISHED");
        event.setPublishedAt(LocalDateTime.now());
        outboxEventRepository.save(event);
    }

    /**
     * 标记事件为失败
     */
    @Transactional
    public void markAsFailed(Long eventId, String errorMessage) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在: " + eventId));

        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(errorMessage);

        if (event.getRetryCount() >= event.getMaxRetries()) {
            event.setStatus("FAILED");
        }

        outboxEventRepository.save(event);
    }
}
