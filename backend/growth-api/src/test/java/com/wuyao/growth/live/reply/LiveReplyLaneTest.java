package com.wuyao.growth.live.reply;

import com.wuyao.growth.common.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.ConversionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class LiveReplyLaneTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LiveReplyLane.class)
            // What a full application provides: durations written as "30m".
            .withBean("conversionService", ConversionService.class, ApplicationConversionService::getSharedInstance)
            .withBean(TaskService.class, () -> mock(TaskService.class));

    @Test
    void whereverLiveRoomsAreWorkedLoopsOfTheirOwnAnswerViewersAndNothingElse() {
        context.withPropertyValues("growth.worker.enabled=true", "growth.worker.queues=DEFAULT,IMAGE,VIDEO,LIVE",
                "growth.live.reply-lane.poll-interval=20").run(running -> {
            LiveReplyLane lane = running.getBean(LiveReplyLane.class);
            assertThat(lane.isRunning()).isTrue();
            assertThat(lane.concurrency()).isEqualTo(3);
            // Each loop asks for work from the replies' queue, and from no other.
            TaskService tasks = running.getBean(TaskService.class);
            verify(tasks, timeout(3000).atLeast(3)).claim("LIVE_REPLY", 1);
            lane.stop();
            assertThat(lane.isRunning()).isFalse();
        });
        context.withPropertyValues("growth.worker.enabled=true", "growth.worker.queues=LIVE", "growth.live.reply-lane.concurrency=1")
                .run(running -> assertThat(running.getBean(LiveReplyLane.class).concurrency()).isEqualTo(1));
    }

    @Test
    void anApiProcessAWorkerForOtherQueuesAndAnExplicitSwitchAllLeaveItOut() {
        context.withPropertyValues("growth.worker.enabled=false", "growth.worker.queues=DEFAULT,IMAGE,VIDEO,LIVE")
                .run(running -> assertThat(running).doesNotHaveBean(LiveReplyLane.class));
        context.withPropertyValues("growth.worker.enabled=true", "growth.worker.queues=VIDEO")
                .run(running -> assertThat(running).doesNotHaveBean(LiveReplyLane.class));
        context.withPropertyValues("growth.worker.enabled=true", "growth.worker.queues=LIVE", "growth.live.reply-lane.enabled=false")
                .run(running -> assertThat(running).doesNotHaveBean(LiveReplyLane.class));
        // Nothing is set at all, as in a unit test or a tool: no loops.
        context.run(running -> assertThat(running).doesNotHaveBean(LiveReplyLane.class));
        // A number of loops that makes no sense is refused at startup rather than quietly corrected.
        context.withPropertyValues("growth.worker.enabled=true", "growth.worker.queues=LIVE", "growth.live.reply-lane.concurrency=0")
                .run(running -> assertThat(running).hasFailed());
    }
}
