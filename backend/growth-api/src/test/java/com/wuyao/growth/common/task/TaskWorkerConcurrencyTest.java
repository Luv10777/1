package com.wuyao.growth.common.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskWorkerConcurrencyTest {
    @Test
    void syntheticImageLoadUsesEveryConfiguredSlot() throws Exception {
        for (int parallelism : List.of(1, 2, 4, 6)) {
            runLoad(parallelism, 48, 120);
        }
    }

    private void runLoad(int parallelism, int taskCount, long handlerMillis) throws Exception {
        TaskService service = mock(TaskService.class);
        Queue<Task> pending = new ConcurrentLinkedQueue<>();
        for (long id = 1; id <= taskCount; id++) {
            Task task = new Task();
            task.setId(id);
            task.setTenantId(7L);
            task.setType("IMAGE_TEST");
            task.setAttempts(1);
            pending.add(task);
        }
        when(service.claim("IMAGE", 1)).thenAnswer(invocation -> {
            Task task = pending.poll();
            return task == null ? List.of() : List.of(task);
        });
        when(service.renew(anyLong(), anyInt())).thenReturn(true);
        when(service.succeed(anyLong(), anyInt(), anyMap())).thenReturn(true);

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(taskCount);
        TaskHandler handler = new TaskHandler() {
            public String type() {
                return "IMAGE_TEST";
            }

            public Map<String, Object> handle(Task task) {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(handlerMillis);
                    return Map.of("done", true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    active.decrementAndGet();
                    completed.countDown();
                }
            }
        };

        TaskWorker worker = new TaskWorker(service, List.of(handler), List.of("IMAGE"),
            16, Duration.ofMinutes(30), 20, parallelism);
        ExecutorService poller = Executors.newSingleThreadExecutor();
        long started = System.nanoTime();
        try {
            poller.submit(() -> {
                while (completed.getCount() > 0) {
                    worker.poll();
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            assertThat(completed.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            poller.shutdownNow();
            worker.stop();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(maxActive.get()).isEqualTo(parallelism);
        System.out.printf("synthetic image load: parallelism=%d tasks=%d maxActive=%d elapsedMs=%d%n",
            parallelism, taskCount, maxActive.get(), elapsedMillis);
    }
}
