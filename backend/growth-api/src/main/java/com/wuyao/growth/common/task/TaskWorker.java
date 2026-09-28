package com.wuyao.growth.common.task;

import com.wuyao.growth.common.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 每个执行槽只领取一条任务；任务采用至少一次执行语义。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "growth.worker.enabled", havingValue = "true")
public class TaskWorker {
    private final TaskService taskService;
    private final Map<String, TaskHandler> handlers;
    private final List<String> queues;
    private final int batchSize;
    private final Map<Long, Task> activeTasks = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final ExecutorService executor;

    @Autowired
    public TaskWorker(TaskService taskService, List<TaskHandler> handlerList,
                      @Value("${growth.worker.queues:DEFAULT}") List<String> queues,
                      @Value("${growth.worker.batch-size:5}") int batchSize,
                      @Value("${growth.worker.lease:30m}") Duration lease,
                      @Value("${growth.worker.heartbeat-interval:10000}") long heartbeatMillis,
                      @Value("${growth.worker.parallelism:1}") int parallelism) {
        if (batchSize < 1 || parallelism < 1 || heartbeatMillis < 1 || lease.toMillis() <= heartbeatMillis * 2) {
            throw new IllegalArgumentException("batch-size 和 parallelism 必须大于零，lease 必须大于两个心跳间隔");
        }
        this.taskService = taskService;
        this.queues = queues;
        this.batchSize = batchSize;
        this.handlers = handlerList.stream().collect(Collectors.toMap(TaskHandler::type, Function.identity()));
        this.slots = new Semaphore(parallelism);
        this.executor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "growth-task-executor");
            thread.setDaemon(false);
            return thread;
        });
    }

    public TaskWorker(TaskService taskService, List<TaskHandler> handlerList,
                      List<String> queues, int batchSize, Duration lease, long heartbeatMillis) {
        this(taskService, handlerList, queues, batchSize, lease, heartbeatMillis, 1);
    }

    @Scheduled(fixedDelayString = "${growth.worker.poll-interval:2000}")
    public void poll() {
        for (String queue : queues) {
            for (int i = 0; i < batchSize && slots.tryAcquire(); i++) {
                Task task = null;
                try {
                    List<Task> claimed = taskService.claim(queue.trim(), 1);
                    if (claimed.isEmpty()) {
                        slots.release();
                        break;
                    }
                    task = claimed.get(0);
                    activeTasks.put(task.getId(), task);
                    Task execution = task;
                    executor.execute(() -> execute(execution));
                } catch (RuntimeException e) {
                    if (task != null) activeTasks.remove(task.getId());
                    slots.release();
                    throw e;
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${growth.worker.heartbeat-interval:10000}")
    public void heartbeat() {
        for (Task task : activeTasks.values()) {
            if (!taskService.renew(task.getId(), task.getAttempts())) {
                log.warn("任务已失去执行租约，后续回写将被拒绝: id={} attempt={}", task.getId(), task.getAttempts());
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void reclaim() {
        taskService.reclaimExpired();
    }

    private void execute(Task task) {
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
            activeTasks.remove(task.getId());
            slots.release();
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
