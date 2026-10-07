package com.wuyao.growth.live.speech;

import com.wuyao.growth.voice.VoiceProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechTimelineTest {
    /** Every character a word of 100 ms, the way the provider reports Chinese. */
    static List<VoiceProvider.Word> timed(String text) {
        List<VoiceProvider.Word> words = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) words.add(new VoiceProvider.Word(text.substring(i, i + 1), i * 100L, i * 100L + 100));
        return words;
    }

    @Test
    void thePauseBeforeASentenceRunsFromTheLastSpokenWordAcrossThePunctuationToItsFirstWord() {
        SpeechTimeline timeline = new SpeechTimeline(timed("九点关门！”好，接着说。"));
        assertThat(timeline.text()).isEqualTo("九点关门！”好，接着说。");
        // “好” is the 7th character: 门 ends at 400, ！ and ” fill 400-600, 好 starts at 600.
        assertThat(timeline.pauseBefore(6)).containsExactly(400, 600);
        // Nothing precedes the first word, and no word starts past the end.
        assertThat(timeline.pauseBefore(0)).isNull();
        assertThat(timeline.pauseBefore(99)).isNull();
    }

    @Test
    void positionsAreFoundInWhatWasSpokenAndAPositionInsideAWordIsNotACut() {
        SpeechTimeline timeline = new SpeechTimeline(List.of(new VoiceProvider.Word("只要", 0, 300),
                new VoiceProvider.Word("九点九", 300, 800), new VoiceProvider.Word("。", 800, 1000), new VoiceProvider.Word("好", 1000, 1200)));
        assertThat(timeline.text()).isEqualTo("只要九点九。好");
        assertThat(timeline.pauseBefore(6)).containsExactly(800, 1000);
        assertThat(timeline.pauseBefore(3)).isNull();
        assertThat(timeline.startOfClosing("好")).isEqualTo(6);
        assertThat(timeline.startOfClosing("好的")).isEqualTo(-1);
        assertThat(timeline.startOfClosing(null)).isEqualTo(-1);
        // A clip that is nothing but the closing line has nothing before it to come back from.
        assertThat(new SpeechTimeline(timed("好。")).startOfClosing("好。")).isEqualTo(-1);
        assertThat(new SpeechTimeline(List.of()).isEmpty()).isTrue();
    }
}
