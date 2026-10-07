package com.wuyao.growth.live.reply;

import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.task.TaskWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Worker loops, in the same process, that do nothing but answer viewers.
 *
 * A worker runs one task at a time. With replies on the queue that narration uses, a reply waited
 * for every script and clip queued before it, twice: once to be judged and once to be synthesised.
 * Measured in a live test, that was 25 to 35 of the 36 to 45 seconds a reply took. On a queue and
 * loops of their own, replies run alongside narration instead of behind it, and several comments
 * are answered at once instead of one after another.
 *
 * The loops start wherever the LIVE queue is worked, so a deployment that already runs a worker
 * for live rooms needs no new process and no new setting. They have their own threads and poll on
 * their own, shorter, interval: nothing here touches the scheduler the other queues share.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${growth.worker.enabled:false}' == 'true' and '${growth.live.reply-lane.enabled:true}' == 'true' "
        + "and '${growth.worker.queues:DEFAULT}'.contains('LIVE')")
public class LiveReplyLane implements SmartLifecycle {
    /** Judging a comment and synthesising its answer; nothing else is ever put here. */
    public static final String QUEUE = "LIVE_REPLY";

    private final List<TaskWorker> workers = new ArrayList<>();
    private final long pollMillis;
    private final long heartbeatMillis;
    private ThreadPoolTaskScheduler scheduler;
    private volatile boolean running;

    /**
     * @param concurrency how many comments are worked on at once. Each one in flight is a request to
     *                    the text model and then to the voice provider, on top of narration's own.
     */
    public LiveReplyLane(TaskService tasks, List<TaskHandler> handlers,
                         @Value("${growth.live.reply-lane.concurrency:3}") int concurrency,
                         @Value("${growth.live.reply-lane.poll-interval:500}") long pollMillis,
                         @Value("${growth.worker.batch-size:5}") int batchSize,
                         @Value("${growth.worker.lease:30m}") Duration lease,
                         @Value("${growth.worker.heartbeat-interval:10000}") long heartbeatMillis) {
        if (concurrency < 1 || concurrency > 16 || pollMillis < 1) {
            throw new IllegalArgumentException("growth.live.reply-lane.concurrency 必须在 1 到 16 之间，poll-interval 必须大于零");
        }
        this.pollMillis = pollMillis;
        this.heartbeatMillis = heartbeatMillis;
        for (int i = 0; i < concurrency; i++) {
            workers.add(new TaskWorker(tasks, handlers, List.of(QUEUE), batchSize, lease, heartbeatMillis));
        }
    }

    @Override
    public synchronized void start() {
        if (running) return;
        scheduler = new ThreadPoolTaskScheduler();
        // One thread for each loop, which a slow reply can hold for many seconds, and one that is
        // always free to renew their leases.
        scheduler.setPoolSize(workers.size() + 1);
        scheduler.setThreadNamePrefix("live-reply-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        for (TaskWorker worker : workers) {
            scheduler.scheduleWithFixedDelay(worker::poll, Duration.ofMillis(pollMillis));
            scheduler.scheduleWithFixedDelay(worker::heartbeat, Duration.ofMillis(heartbeatMillis));
        }
        running = true;
        log.info("弹幕回复通道就绪: queue={} concurrency={}", QUEUE, workers.size());
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int concurrency() {
        return workers.size();
    }
}
