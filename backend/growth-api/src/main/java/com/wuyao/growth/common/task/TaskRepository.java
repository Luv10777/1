package com.wuyao.growth.common.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {
    /** 行锁只保护领取事务；过期重试仍可能重复执行，handler 必须幂等。 */
    @Query(value = """
            SELECT * FROM tasks
             WHERE status = 'PENDING' AND queue = :queue
               AND run_after <= clock_timestamp() AND attempts < max_attempts
             ORDER BY priority DESC, id
             FOR UPDATE SKIP LOCKED LIMIT :limit
            """, nativeQuery = true)
    List<Task> claimBatch(@Param("queue") String queue, @Param("limit") int limit);

    @Modifying
    @Query(value = """
            INSERT INTO tasks(tenant_id, created_by, type, queue, payload, idempotency_key)
            VALUES (:tenantId, :createdBy, :type, :queue, CAST(:payload AS jsonb), :key)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") Long tenantId, @Param("createdBy") Long createdBy,
                       @Param("type") String type, @Param("queue") String queue,
                       @Param("payload") String payload, @Param("key") String key);

    /** attempts 是单调递增的执行轮次。旧轮次和过期租约没有回写权限。 */
    @Query(value = """
            SELECT * FROM tasks WHERE id = :id AND status = 'RUNNING'
              AND attempts = :attempt AND lease_expires_at > clock_timestamp()
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Task> findOwnedRunning(@Param("id") Long id, @Param("attempt") int attempt);

    @Modifying
    @Query(value = """
            UPDATE tasks SET lease_expires_at = clock_timestamp() + :leaseMillis * interval '1 millisecond',
                             updated_at = clock_timestamp()
             WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
               AND lease_expires_at > clock_timestamp()
            """, nativeQuery = true)
    int renew(@Param("id") Long id, @Param("attempt") int attempt, @Param("leaseMillis") long leaseMillis);

    @Query(value = """
            UPDATE tasks SET
                status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                lease_expires_at = NULL,
                finished_at = CASE WHEN attempts >= max_attempts THEN clock_timestamp() ELSE NULL END,
                run_after = clock_timestamp() + power(2, LEAST(attempts, 10)) * interval '10 seconds',
                error_code = 'LEASE_EXPIRED', error_message = '执行租约已过期', updated_at = clock_timestamp()
             WHERE (status = 'RUNNING' AND lease_expires_at <= clock_timestamp())
                OR (status = 'PENDING' AND attempts >= max_attempts)
            RETURNING id
            """, nativeQuery = true)
    List<Long> reclaimExpired();

    Optional<Task> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    Page<Task> findByTenantIdOrderByIdDesc(Long tenantId, Pageable pageable);
}
