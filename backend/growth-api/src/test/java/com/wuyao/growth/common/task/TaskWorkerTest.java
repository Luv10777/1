package com.wuyao.growth.common.task;

import com.wuyao.growth.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskWorkerTest {
    @Test
    void longHandlerDoesNotBlockHeartbeatOrPreclaimWaitingTasks() throws Exception {
        TaskService service = mock(TaskService.class);
        Task first = task(1L, "VIDEO_TEST");
        Task second = task(2L, "VIDEO_TEST");
        when(service.claim("VIDEO", 1)).thenReturn(List.of(first), List.of(second), List.of());
        when(service.renew(anyLong(), anyInt())).thenReturn(true);
        when(service.succeed(anyLong(), anyInt(), anyMap())).thenReturn(true);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(2);
        TaskHandler handler = new TaskHandler() {
            public String type() { return "VIDEO_TEST"; }
            public Map<String, Object> handle(Task task) {
                assertThat(TenantContext.require()).isEqualTo(7L);
                if (task.getId() == 1L) {
                    started.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                completed.countDown();
                return Map.of("done", true);
            }
        };
        var worker = new TaskWorker(service, List.of(handler), List.of("VIDEO"), 2, Duration.ofMinutes(30), 20);
        var scheduler = new WorkerSchedulingConfig().taskScheduler();
        scheduler.initialize();
        var heartbeat = scheduler.scheduleWithFixedDelay(worker::heartbeat, Duration.ofMillis(20));
        try {
            scheduler.execute(worker::poll);
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            verify(service, timeout(3000).atLeastOnce()).renew(1L, 1);
            verify(service, times(1)).claim("VIDEO", 1);
            release.countDown();
            verify(service, timeout(3000)).succeed(eq(1L), eq(1), anyMap());
            for (int i = 0; i < 20 && completed.getCount() > 0; i++) {
                worker.poll();
                Thread.sleep(20);
            }
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            verify(service, timeout(3000).times(2)).succeed(anyLong(), eq(1), anyMap());
            verify(service, times(2)).claim("VIDEO", 1);
        } finally {
            release.countDown();
            heartbeat.cancel(false);
            scheduler.shutdown();
            worker.stop();
        }
    }

    @Test
    void parallelismRunsIndependentTasksWithoutPreclaimingBeyondCapacity() throws Exception {
        TaskService service = mock(TaskService.class);
        Task first = task(1L, "IMAGE_TEST");
        Task second = task(2L, "IMAGE_TEST");
        when(service.claim("IMAGE", 1)).thenReturn(List.of(first), List.of(second), List.of());
        when(service.renew(anyLong(), anyInt())).thenReturn(true);
        when(service.succeed(anyLong(), anyInt(), anyMap())).thenReturn(true);
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        TaskHandler handler = new TaskHandler() {
            public String type() { return "IMAGE_TEST"; }
            public Map<String, Object> handle(Task task) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return Map.of("done", true);
            }
        };
        var worker = new TaskWorker(service, List.of(handler), List.of("IMAGE"), 5,
            Duration.ofMinutes(30), 20, 2);
        try {
            worker.poll();
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            verify(service, times(2)).claim("IMAGE", 1);
            worker.heartbeat();
            verify(service).renew(1L, 1);
            verify(service).renew(2L, 1);
            release.countDown();
            verify(service, timeout(3000).times(2)).succeed(anyLong(), eq(1), anyMap());
        } finally {
            release.countDown();
            worker.stop();
        }
    }

    @Test
    void failedTaskReleasesItsExecutionSlot() {
        TaskService service = mock(TaskService.class);
        when(service.claim("IMAGE", 1)).thenReturn(
            List.of(task(1L, "IMAGE_TEST")), List.of(task(2L, "IMAGE_TEST")), List.of());
        when(service.fail(anyLong(), anyInt(), anyString(), anyString())).thenReturn(true);
        when(service.succeed(anyLong(), anyInt(), anyMap())).thenReturn(true);
        TaskHandler handler = new TaskHandler() {
            public String type() { return "IMAGE_TEST"; }
            public Map<String, Object> handle(Task task) {
                if (task.getId() == 1L) throw new IllegalStateException("synthetic failure");
                return Map.of("done", true);
            }
        };
        var worker = new TaskWorker(service, List.of(handler), List.of("IMAGE"), 1,
            Duration.ofMinutes(30), 20, 1);
        try {
            worker.poll();
            verify(service, timeout(3000)).fail(eq(1L), eq(1), eq("HANDLER_ERROR"), anyString());
            worker.poll();
            verify(service, timeout(3000)).succeed(eq(2L), eq(1), anyMap());
        } finally {
            worker.stop();
        }
    }

    private Task task(Long id, String type) {
        Task task = new Task();
        task.setId(id);
        task.setTenantId(7L);
        task.setType(type);
        task.setAttempts(1);
        return task;
    }
}
