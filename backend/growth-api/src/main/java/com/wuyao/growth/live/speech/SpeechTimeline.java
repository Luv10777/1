package com.wuyao.growth.live.speech;

import com.wuyao.growth.voice.VoiceProvider;

import java.util.List;

/**
 * The words of a clip laid end to end as text, with the time each one is heard. The text is the
 * provider's spoken form, not what was submitted: positions are found in it, never carried over
 * from the original.
 */
final class SpeechTimeline {
    private final List<VoiceProvider.Word> words;
    private final int[] offsets;
    private final String text;

    SpeechTimeline(List<VoiceProvider.Word> words) {
        this.words = words;
        this.offsets = new int[words.size()];
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            offsets[i] = joined.length();
            joined.append(words.get(i).text());
        }
        this.text = joined.toString();
    }

    boolean isEmpty() {
        return words.isEmpty();
    }

    String text() {
        return text;
    }

    /** @return where a closing line starts when the clip ends with exactly that line, otherwise -1 */
    int startOfClosing(String closing) {
        return closing != null && !closing.isBlank() && text.length() > closing.length() && text.endsWith(closing)
                ? text.length() - closing.length() : -1;
    }

    /**
     * The stretch of time in which the speaker is between the word that starts at this character and
     * whatever was said before it: from the end of the last spoken word, across any punctuation, to
     * the start of this one.
     *
     * @return {from, to} in milliseconds, or null when no word starts there or nothing precedes it
     */
    long[] pauseBefore(int offset) {
        int index = java.util.Arrays.binarySearch(offsets, offset);
        if (index <= 0) return null;
        int previous = index - 1;
        while (previous > 0 && !spoken(words.get(previous).text())) previous--;
        VoiceProvider.Word before = words.get(previous);
        return new long[]{spoken(before.text()) ? before.endMillis() : before.beginMillis(), words.get(index).beginMillis()};
    }

    private static boolean spoken(String word) {
        return word.codePoints().anyMatch(Character::isLetterOrDigit);
    }
}
