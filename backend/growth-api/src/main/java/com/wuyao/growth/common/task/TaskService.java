package com.wuyao.growth.common.task;

import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    /** 抢到任务后租约多久过期。worker 崩了，超过这个时间任务会被别人捡走。 */
    private static final Duration LEASE = Duration.ofMinutes(30);

    private final TaskRepository repository;

    /**
     * 提交任务。给了 idempotencyKey 的话，重复提交返回原来那条，不会重复扣费。
     */
    @Transactional
    public Task submit(String type, String queue, Map<String, Object> payload,
                       String idempotencyKey, Long createdBy) {
        Long tenantId = TenantContext.require();

        if (idempotencyKey != null) {
            var existing = repository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                log.debug("幂等命中，返回已有任务: id={}", existing.get().getId());
                return existing.get();
            }
        }

        Task task = new Task();
        task.setTenantId(tenantId);
        task.setCreatedBy(createdBy);
        task.setType(type);
        task.setQueue(queue == null ? "DEFAULT" : queue);
        task.setPayload(payload == null ? Map.of() : payload);
        task.setIdempotencyKey(idempotencyKey);
        return repository.save(task);
    }

    /**
     * 抢一批任务并立刻标记 RUNNING。
     * 必须是独立短事务：行锁只在这个事务里持有，标记完就提交放锁，
     * 真正干活在事务外面，不占着锁跑几十分钟。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Task> claim(String queue, int limit) {
        List<Task> claimed = repository.claimBatch(queue, limit);
        Instant now = Instant.now();
        for (Task t : claimed) {
            t.setStatus(TaskStatus.RUNNING);
            t.setStartedAt(now);
            t.setLeaseExpiresAt(now.plus(LEASE));
            t.setAttempts(t.getAttempts() + 1);
        }
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long taskId, Map<String, Object> result) {
        repository.findById(taskId).ifPresent(t -> {
            t.setStatus(TaskStatus.SUCCEEDED);
            t.setResult(result);
            t.setFinishedAt(Instant.now());
            t.setLeaseExpiresAt(null);
        });
    }

    /** 失败：还有重试次数就退避后放回队列，用完了才真的置为 FAILED。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long taskId, String errorCode, String errorMessage) {
        repository.findById(taskId).ifPresent(t -> {
            t.setErrorCode(errorCode);
            t.setErrorMessage(errorMessage);
            t.setLeaseExpiresAt(null);
            if (t.getAttempts() < t.getMaxAttempts()) {
                long backoffSeconds = (long) Math.pow(2, t.getAttempts()) * 10;
                t.setStatus(TaskStatus.PENDING);
                t.setRunAfter(Instant.now().plusSeconds(backoffSeconds));
                log.warn("任务失败将重试: id={} 第{}次 {}秒后", taskId, t.getAttempts(), backoffSeconds);
            } else {
                t.setStatus(TaskStatus.FAILED);
                t.setFinishedAt(Instant.now());
                log.error("任务最终失败: id={} {}", taskId, errorMessage);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reclaimExpired() {
        List<Long> ids = repository.reclaimExpired();
        if (!ids.isEmpty()) {
            log.warn("回收租约过期的任务 {} 条: {}", ids.size(), ids);
        }
        return ids.size();
    }
}
