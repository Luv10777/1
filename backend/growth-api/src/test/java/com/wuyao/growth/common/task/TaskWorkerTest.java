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
        Task first = task(1L);
        Task second = task(2L);
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
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            verify(service, timeout(3000).times(2)).succeed(anyLong(), eq(1), anyMap());
            verify(service, times(2)).claim("VIDEO", 1);
        } finally {
            release.countDown();
            heartbeat.cancel(false);
            scheduler.shutdown();
        }
    }

    private Task task(Long id) {
        Task task = new Task();
        task.setId(id);
        task.setTenantId(7L);
        task.setType("VIDEO_TEST");
        task.setAttempts(1);
        return task;
    }
}
