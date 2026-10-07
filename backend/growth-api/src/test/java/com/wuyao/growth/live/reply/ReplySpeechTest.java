package com.wuyao.growth.live.reply;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplySpeechTest {
    @Test
    void theLeadInQuotesTheMerchantsWordingOfTheQuestionAndLeavesTheAnswerUntouched() {
        String spoken = ReplySpeech.withLeadIn("几点关门？ ", "我们每天晚上 9 点关门。", "comment:abc");
        assertThat(spoken).endsWith("问几点关门，我们每天晚上 9 点关门。").doesNotContain("？");
        // The same comment always gets the same words, so a retried task cannot say something else.
        assertThat(ReplySpeech.withLeadIn("几点关门？ ", "我们每天晚上 9 点关门。", "comment:abc")).isEqualTo(spoken);
    }

    @Test
    void aQuestionTooLongToReadOutOrMissingIsOnlyAnnounced() {
        String longQuestion = "问".repeat(ReplySpeech.MAX_QUESTION_CHARS + 1);
        assertThat(ReplySpeech.withLeadIn(longQuestion, "答案。", "s")).isEqualTo("回答一下弹幕里的问题，答案。");
        assertThat(ReplySpeech.withLeadIn(" ", "答案。", "s")).isEqualTo("回答一下弹幕里的问题，答案。");
        assertThat(ReplySpeech.withLeadIn(null, "答案。", null)).isEqualTo("回答一下弹幕里的问题，答案。");
    }

    @Test
    void onlyAHostAnsweringInTheMiddleOfNarrationSaysALineToGetBackToIt() {
        assertThat(ReplySpeech.bridge("comment:abc", true, false)).isEqualTo(ReplySpeech.bridge("comment:abc", true, false)).endsWith("。");
        // The host's voice returning is the hand-over; a co-host announcing it every time is noise.
        assertThat(ReplySpeech.bridge("comment:abc", true, true)).isNull();
        assertThat(ReplySpeech.bridge("comment:abc", false, false)).isNull();
    }
}
