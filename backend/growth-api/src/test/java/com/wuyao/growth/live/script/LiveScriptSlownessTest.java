package com.wuyao.growth.live.script;

import com.wuyao.growth.live.speech.LiveSpeechItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LiveScriptSlownessTest {
    private final Instant now = Instant.parse("2026-10-04T06:12:00Z");

    @Test
    void aHealthyItemNeedsNoExplanation() {
        assertThat(LiveScriptService.slowness(item(LiveSpeechItem.GENERATING, 5, 5, null), now)).isNull();
        assertThat(LiveScriptService.slowness(item(LiveSpeechItem.GENERATING, 15, 15, 12L), now)).isNull();
    }

    @Test
    void anItemNobodyPickedUpPointsAtTheWorker() {
        assertThat(LiveScriptService.slowness(item(LiveSpeechItem.PENDING, 44, 44, null), now))
                .contains("已排队 44 秒", "还没有 worker 开始处理", "LIVE");
    }

    @Test
    void anItemAWorkerIsBusyWithPointsAtTheSlowProviderAndNotAtTheWorker() {
        assertThat(LiveScriptService.slowness(item(LiveSpeechItem.GENERATING, 46, 46, 44L), now))
                .contains("文案模型已处理 44 秒", "worker 在正常工作", "思考模式").doesNotContain("请确认后台 worker");
        assertThat(LiveScriptService.slowness(item(LiveSpeechItem.PENDING, 60, 28, 25L), now))
                .contains("语音合成已处理 25 秒", "worker 在正常工作");
    }

    @Test
    void timeSpentWritingIsNotCountedAsWaitingForSynthesis() {
        // Created a minute ago, but its text was only just finished: it has waited two seconds, not sixty.
        assertThat(LiveScriptService.slowness(item(LiveSpeechItem.PENDING, 60, 2, null), now)).isNull();
    }

    @Test
    void rowsFromBeforeTheTimestampsExistedFallBackToTheirCreationTime() {
        LiveSpeechItem legacy = item(LiveSpeechItem.PENDING, 90, 0, null);
        legacy.setQueuedAt(null);
        assertThat(LiveScriptService.slowness(legacy, now)).contains("已排队 90 秒");
    }

    private LiveSpeechItem item(String status, long createdSecondsAgo, long queuedSecondsAgo, Long startedSecondsAgo) {
        LiveSpeechItem item = new LiveSpeechItem();
        item.setStatus(status);
        item.setCreatedAt(now.minusSeconds(createdSecondsAgo));
        item.setQueuedAt(now.minusSeconds(queuedSecondsAgo));
        item.setStartedAt(startedSecondsAgo == null ? null : now.minusSeconds(startedSecondsAgo));
        return item;
    }
}
