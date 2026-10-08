package com.wuyao.growth.live.reply;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReplySpeechTest {
    @Test
    void mostRepliesOpenOnTheSubjectAndOnlySomeAreIntroducedAsAViewersQuestionInVaryingWords() {
        List<ReplySpeech.Opening> openings = IntStream.range(0, 300).mapToObj(i -> ReplySpeech.opening("comment:" + i)).toList();
        long attributed = openings.stream().filter(opening -> opening.attribution() != null).count();
        // About one in three, not every one and not none.
        assertThat(attributed).isBetween(60L, 140L);
        assertThat(openings.stream().map(ReplySpeech.Opening::attribution).filter(Objects::nonNull).distinct())
                .hasSizeGreaterThanOrEqualTo(4).allMatch(words -> words.endsWith("问") || words.endsWith("想知道"));
        // The same comment always opens the same way, so a redraft or a retried task cannot change it.
        assertThat(ReplySpeech.opening("comment:abc")).isEqualTo(ReplySpeech.opening("comment:abc"));
        assertThat(ReplySpeech.opening(null)).isNotNull();
    }

    @Test
    void aSavedAnswerSpokenAsItStandsIsGivenTheMerchantsWordingOfTheQuestionInFrontAndIsOtherwiseUntouched() {
        List<String> spoken = IntStream.range(0, 300)
                .mapToObj(i -> ReplySpeech.withLeadIn("几点关门？ ", "我们每天晚上 9 点关门。", "comment:" + i)).toList();
        assertThat(spoken).allMatch(text -> text.endsWith("我们每天晚上 9 点关门。") && text.contains("几点关门") && !text.contains("？"));
        // Not every one of them says "有朋友问": most name the subject, and the words vary.
        assertThat(spoken.stream().filter(text -> text.startsWith("关于") || text.startsWith("说到") || text.startsWith("几点关门这个问题")).count())
                .isBetween(160L, 240L);
        assertThat(spoken.stream().distinct()).hasSizeGreaterThanOrEqualTo(6);
        // The same comment always gets the same words, so a retried task cannot say something else.
        assertThat(ReplySpeech.withLeadIn("几点关门？ ", "我们每天晚上 9 点关门。", "comment:abc"))
                .isEqualTo(ReplySpeech.withLeadIn("几点关门？ ", "我们每天晚上 9 点关门。", "comment:abc"));
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
