package com.wuyao.growth.common.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuyao.growth.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final TaskRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${growth.worker.lease:30m}")
    private Duration lease;

    /** 插入与业务写入共用事务。并发冲突等待胜出的事务提交后返回同一任务。 */
    @Transactional
    public Task submit(String type, String queue, Map<String, Object> payload,
                       String idempotencyKey, Long createdBy) {
        Long tenantId = TenantContext.require();
        String actualQueue = queue == null ? "DEFAULT" : queue;
        Map<String, Object> actualPayload = payload == null ? Map.of() : payload;
        if (idempotencyKey != null) {
            try {
                repository.insertIfAbsent(tenantId, createdBy, type, actualQueue,
                        objectMapper.writeValueAsString(actualPayload), idempotencyKey);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("任务 payload 必须可序列化为 JSON", e);
            }
            return repository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey).orElseThrow();
        }
        Task task = new Task();
        task.setTenantId(tenantId);
        task.setCreatedBy(createdBy);
        task.setType(type);
        task.setQueue(actualQueue);
        task.setPayload(actualPayload);
        return repository.save(task);
    }

    /** 独立短事务；调用方只领取能够立刻执行的数量。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Task> claim(String queue, int limit) {
        if (limit < 1 || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("领取数量和租约时长必须大于零");
        }
        List<Task> claimed = repository.claimBatch(queue, limit);
        Instant now = Instant.now();
        for (Task task : claimed) {
            task.setStatus(TaskStatus.RUNNING);
            task.setStartedAt(now);
            task.setFinishedAt(null);
            task.setLeaseExpiresAt(now.plus(lease));
            task.setAttempts(task.getAttempts() + 1);
        }
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renew(Long taskId, int attempt) {
        return repository.renew(taskId, attempt, lease.toMillis()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean succeed(Long taskId, int attempt, Map<String, Object> result) {
        var owned = repository.findOwnedRunning(taskId, attempt);
        if (owned.isEmpty()) return false;
        Task task = owned.get();
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setResult(result);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setFinishedAt(Instant.now());
        task.setLeaseExpiresAt(null);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(Long taskId, int attempt, String errorCode, String errorMessage) {
        var owned = repository.findOwnedRunning(taskId, attempt);
        if (owned.isEmpty()) return false;
        Task task = owned.get();
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setLeaseExpiresAt(null);
        if (task.getAttempts() < task.getMaxAttempts()) {
            long backoffSeconds = (1L << Math.min(task.getAttempts(), 10)) * 10;
            task.setStatus(TaskStatus.PENDING);
            task.setRunAfter(Instant.now().plusSeconds(backoffSeconds));
        } else {
            task.setStatus(TaskStatus.FAILED);
            task.setFinishedAt(Instant.now());
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reclaimExpired() {
        List<Long> ids = repository.reclaimExpired();
        if (!ids.isEmpty()) log.warn("回收租约过期或重试耗尽的任务: {}", ids);
        return ids.size();
    }
}
