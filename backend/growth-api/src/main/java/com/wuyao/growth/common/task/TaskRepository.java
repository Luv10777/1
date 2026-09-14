package com.wuyao.growth.common.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * worker 抢任务。整套调度机制就靠这一句。
     *
     * FOR UPDATE SKIP LOCKED —— 别的 worker 已经锁住的行直接跳过，不排队等待。
     * 所以想起几个 worker 就起几个，同一行永远只会被一个进程拿到，
     * 不需要消息队列，也不需要分布式锁。
     */
    @Query(value = """
            SELECT * FROM tasks
             WHERE status = 'PENDING'
               AND queue = :queue
               AND run_after <= now()
             ORDER BY priority DESC, id
             FOR UPDATE SKIP LOCKED
             LIMIT :limit
            """, nativeQuery = true)
    List<Task> claimBatch(@Param("queue") String queue, @Param("limit") int limit);

    /**
     * 回收租约过期的任务：worker 进程崩了，它手上的任务会卡在 RUNNING。
     * 定期把这些放回 PENDING，谁都能再抢。
     */
    @Query(value = """
            UPDATE tasks
               SET status = 'PENDING', lease_expires_at = NULL, updated_at = now()
             WHERE status = 'RUNNING' AND lease_expires_at < now()
            RETURNING id
            """, nativeQuery = true)
    List<Long> reclaimExpired();

    Optional<Task> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    Page<Task> findByTenantIdOrderByIdDesc(Long tenantId, Pageable pageable);
}
