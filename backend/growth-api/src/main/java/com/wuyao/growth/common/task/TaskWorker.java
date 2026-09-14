package com.wuyao.growth.common.task;

import com.wuyao.growth.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * worker 进程的主循环。只在 growth.worker.enabled=true 时装配。
 *
 * 这里用 @Scheduled 是安全的——抢占靠 SKIP LOCKED，
 * 起 N 个实例也不会重复消费，所以不需要 ShedLock 那类分布式锁。
 * （老后端的 @Scheduled 直接操作业务数据，所以只能单实例跑。区别在这里。）
 *
 * 不同队列可以起不同进程组：
 *   图片队列  --growth.worker.queues=IMAGE
 *   视频队列  --growth.worker.queues=VIDEO
 * 这样 20 分钟的视频任务不会把 30 秒的图片任务堵死。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "growth.worker.enabled", havingValue = "true")
public class TaskWorker {

    private final TaskService taskService;
    private final Map<String, TaskHandler> handlers;
    private final List<String> queues;

    public TaskWorker(TaskService taskService,
                      List<TaskHandler> handlerList,
                      @Value("${growth.worker.queues:DEFAULT}") List<String> queues) {
        this.taskService = taskService;
        this.queues = queues;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(TaskHandler::type, Function.identity()));
        log.info("worker 启动。队列={} 已注册处理器={}", queues, handlers.keySet());
    }

    @Scheduled(fixedDelayString = "${growth.worker.poll-interval:2000}")
    public void poll() {
        int batch = 5;
        for (String queue : queues) {
            List<Task> tasks = taskService.claim(queue.trim(), batch);
            for (Task task : tasks) {
                execute(task);
            }
        }
    }

    /** 每分钟把崩溃 worker 遗留的任务放回队列。 */
    @Scheduled(fixedDelay = 60_000)
    public void reclaim() {
        taskService.reclaimExpired();
    }

    private void execute(Task task) {
        TaskHandler handler = handlers.get(task.getType());
        if (handler == null) {
            taskService.fail(task.getId(), "NO_HANDLER", "没有注册处理器: " + task.getType());
            return;
        }
        try {
            // 切进任务所属租户，之后 handler 里访问业务表就受 RLS 保护
            Map<String, Object> result =
                    TenantContext.runAs(task.getTenantId(), () -> handler.handle(task));
            taskService.succeed(task.getId(), result);
            log.info("任务完成: id={} type={}", task.getId(), task.getType());
        } catch (Exception e) {
            log.warn("任务执行异常: id={} type={}", task.getId(), task.getType(), e);
            taskService.fail(task.getId(), "HANDLER_ERROR", e.getMessage());
        }
    }
}
