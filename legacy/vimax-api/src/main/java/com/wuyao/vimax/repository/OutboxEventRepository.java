package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Outbox 事件 Repository
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 查询待发布的事件（未发布且重试次数 < 5）
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL " +
           "AND e.retryCount < 5 " +
           "ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEvents();

    /**
     * 查询发布失败的事件（重试次数 >= 5）
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL " +
           "AND e.retryCount >= 5")
    List<OutboxEvent> findFailedEvents();
}
