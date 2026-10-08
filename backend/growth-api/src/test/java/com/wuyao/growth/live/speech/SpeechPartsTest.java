package com.wuyao.growth.live.speech;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechPartsTest {
    /** The text cut at the offsets, so a test reads as sentences rather than numbers. */
    private static List<String> parts(String text) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        for (int start : SpeechParts.starts(text)) {
            parts.add(text.substring(from, start));
            from = start;
        }
        parts.add(text.substring(from));
        return parts;
    }

    @Test
    void aNewPartStartsAfterEachSentenceAndShortSentencesStayWithTheirNeighbour() {
        assertThat(parts("天热没胃口的时候，是不是想来点清爽的？来！看看这张绿豆牛奶冰兑换券。只要九点九元一张，过期自动退。"))
                .containsExactly("天热没胃口的时候，是不是想来点清爽的？", "来！看看这张绿豆牛奶冰兑换券。", "只要九点九元一张，过期自动退。");
        // A decimal point, a thousands separator and a clock time are not places to stop.
        assertThat(parts("原价 1,000 元现在 9.9 元。营业到 21:30 结束哦。"))
                .containsExactly("原价 1,000 元现在 9.9 元。", "营业到 21:30 结束哦。");
        // A short closing sentence joins the one before it; a quote stays with its sentence.
        assertThat(parts("老板说：“今天现煮的最好喝！”喜欢的朋友可以看看。好吧？"))
                .containsExactly("老板说：“今天现煮的最好喝！”", "喜欢的朋友可以看看。好吧？");
        assertThat(SpeechParts.starts("没有标点的一句话")).isEmpty();
        assertThat(SpeechParts.starts("")).isEmpty();
    }

    @Test
    void aSentenceTooLongToWaitOutMayAlsoBeLeftAtACommaButNotAfterAFewWords() {
        assertThat(parts("家人们，我知道很多人看到这种卡券心里会犯嘀咕，万一买多了吃不完，或者忙忘了时间咋办，别担心，过期没用的话系统自动给你退款。短句不拆，对吧。"))
                .containsExactly("家人们，我知道很多人看到这种卡券心里会犯嘀咕，", "万一买多了吃不完，或者忙忘了时间咋办，",
                        "别担心，过期没用的话系统自动给你退款。", "短句不拆，对吧。");
        // The provider reads a space as “、”; that is not a pause worth stopping at.
        assertThat(SpeechParts.starts("只要、九点九元一张，十、月、三十一日前都能用。")).isEmpty();
    }

    @Test
    void whatIsSentToTheSynthesiserTurnsLineBreaksIntoSentenceEndsAndKeepsAClosingLineApart() {
        assertThat(SpeechParts.spoken("这款蜂蜜口感清甜", null)).isEqualTo("这款蜂蜜口感清甜");
        assertThat(SpeechParts.spoken("第一段话\n\n第二段话。\n第三段", " ")).isEqualTo("第一段话。第二段话。第三段");
        assertThat(SpeechParts.spoken("每天九点营业", "好，咱们接着说。")).isEqualTo("每天九点营业。好，咱们接着说。");
        assertThat(SpeechParts.spoken("老板说：“九点关门！”", "好，我们继续。")).isEqualTo("老板说：“九点关门！”好，我们继续。");
    }
}
