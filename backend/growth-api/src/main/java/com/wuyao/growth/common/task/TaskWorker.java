package com.wuyao.growth.common.task;

import com.wuyao.growth.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 单实例串行执行；需要并发时启动多个 worker。任务采用至少一次执行语义。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "growth.worker.enabled", havingValue = "true")
public class TaskWorker {
    private final TaskService taskService;
    private final Map<String, TaskHandler> handlers;
    private final List<String> queues;
    private final int batchSize;
    private volatile Task activeTask;

    public TaskWorker(TaskService taskService, List<TaskHandler> handlerList,
                      @Value("${growth.worker.queues:DEFAULT}") List<String> queues,
                      @Value("${growth.worker.batch-size:5}") int batchSize,
                      @Value("${growth.worker.lease:30m}") Duration lease,
                      @Value("${growth.worker.heartbeat-interval:10000}") long heartbeatMillis) {
        if (batchSize < 1 || heartbeatMillis < 1 || lease.toMillis() <= heartbeatMillis * 2) {
            throw new IllegalArgumentException("batch-size 必须大于零，lease 必须大于两个心跳间隔");
        }
        this.taskService = taskService;
        this.queues = queues;
        this.batchSize = batchSize;
        this.handlers = handlerList.stream().collect(Collectors.toMap(TaskHandler::type, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${growth.worker.poll-interval:2000}")
    public void poll() {
        for (String queue : queues) {
            for (int i = 0; i < batchSize; i++) {
                // 上一个执行结束才领取下一个，等待中的任务不提前占用租约。
                List<Task> claimed = taskService.claim(queue.trim(), 1);
                if (claimed.isEmpty()) break;
                execute(claimed.get(0));
            }
        }
    }

    @Scheduled(fixedDelayString = "${growth.worker.heartbeat-interval:10000}")
    public void heartbeat() {
        Task task = activeTask;
        if (task != null && !taskService.renew(task.getId(), task.getAttempts())) {
            log.warn("任务已失去执行租约，后续回写将被拒绝: id={} attempt={}", task.getId(), task.getAttempts());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void reclaim() {
        taskService.reclaimExpired();
    }

    private void execute(Task task) {
        activeTask = task;
        try {
            TaskHandler handler = handlers.get(task.getType());
            if (handler == null) {
                taskService.fail(task.getId(), task.getAttempts(), "NO_HANDLER", "没有注册处理器: " + task.getType());
                return;
            }
            Map<String, Object> result = TenantContext.runAs(task.getTenantId(), () -> handler.handle(task));
            if (taskService.succeed(task.getId(), task.getAttempts(), result)) {
                log.info("任务完成: id={} type={}", task.getId(), task.getType());
            }
        } catch (Exception e) {
            log.warn("任务执行异常: id={} type={}", task.getId(), task.getType(), e);
            taskService.fail(task.getId(), task.getAttempts(), "HANDLER_ERROR", e.getMessage());
        } finally {
            activeTask = null;
        }
    }
}
