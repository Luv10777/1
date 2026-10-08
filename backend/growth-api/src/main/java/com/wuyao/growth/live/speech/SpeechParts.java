package com.wuyao.growth.live.speech;

import java.util.ArrayList;
import java.util.List;

/**
 * Where, in a spoken text, a speaker could be interrupted and pick up again: at the start of each
 * sentence, and at a comma inside a sentence too long to wait out. The text is synthesised in one
 * piece; these positions are only looked up in the timing that comes back with the audio.
 */
public final class SpeechParts {
    /** Shorter sentences are counted with their neighbour rather than offered as places to stop. */
    static final int MIN_SENTENCE = 8;
    /** A sentence longer than this may also be left at a comma, so a reply is not kept waiting for its end. */
    static final int LONG_SENTENCE = 40;
    static final int MIN_CLAUSE = 18;

    private static final String SENTENCE_END = "。！？!?；;…";
    private static final String CLOSERS = "”’」』）)\"";
    private static final String CLAUSE_END = "，,：:";

    private SpeechParts() { }

    /** @return the character offsets at which a new part begins, in order; the start of the text is not one */
    public static List<Integer> starts(String text) {
        List<Integer> starts = new ArrayList<>();
        for (int[] sentence : merged(cut(text, 0, text.length(), SENTENCE_END), MIN_SENTENCE)) {
            List<int[]> pieces = sentence[1] - sentence[0] > LONG_SENTENCE
                    ? merged(cut(text, sentence[0], sentence[1], CLAUSE_END), MIN_CLAUSE) : List.of(sentence);
            for (int[] piece : pieces) starts.add(piece[0]);
        }
        return starts.isEmpty() ? starts : List.copyOf(starts.subList(1, starts.size()));
    }

    /**
     * The text as it is handed to the synthesiser: line breaks become sentence ends, and a closing
     * line is appended after a full stop so that it is spoken as a sentence of its own.
     */
    public static String spoken(String text, String outro) {
        StringBuilder spoken = new StringBuilder();
        String[] paragraphs = text.strip().split("\\s*\\R\\s*");
        boolean closing = outro != null && !outro.isBlank();
        for (int i = 0; i < paragraphs.length; i++) {
            if (paragraphs[i].isBlank()) continue;
            spoken.append(paragraphs[i]);
            boolean followed = i < paragraphs.length - 1 || closing;
            if (followed && !endsSentence(paragraphs[i])) spoken.append('。');
        }
        return closing ? spoken.append(outro.strip()).toString() : spoken.toString();
    }

    private static boolean endsSentence(String text) {
        int last = text.length() - 1;
        while (last >= 0 && CLOSERS.indexOf(text.charAt(last)) >= 0) last--;
        return last >= 0 && SENTENCE_END.indexOf(text.charAt(last)) >= 0;
    }

    /** Splits a range after each run of the given marks; a closing quote stays with what it closes. */
    private static List<int[]> cut(String text, int from, int to, String marks) {
        List<int[]> pieces = new ArrayList<>();
        int start = from;
        for (int i = from; i < to; i++) {
            if (marks.indexOf(text.charAt(i)) < 0 || betweenDigits(text, i)) continue;
            int end = i + 1;
            while (end < to && (marks.indexOf(text.charAt(end)) >= 0 || CLOSERS.indexOf(text.charAt(end)) >= 0
                    || Character.isWhitespace(text.charAt(end)))) end++;
            pieces.add(new int[]{start, end});
            start = end;
            i = end - 1;
        }
        if (start < to) pieces.add(new int[]{start, to});
        return pieces;
    }

    /** 1,000 and 12:30 are one number each, not two clauses. */
    private static boolean betweenDigits(String text, int at) {
        return at > 0 && at + 1 < text.length() && Character.isDigit(text.charAt(at - 1)) && Character.isDigit(text.charAt(at + 1));
    }

    /** Joins a piece shorter than the minimum to the one after it; a short last piece joins the one before. */
    private static List<int[]> merged(List<int[]> pieces, int minimum) {
        List<int[]> result = new ArrayList<>();
        int[] pending = null;
        for (int[] piece : pieces) {
            pending = pending == null ? new int[]{piece[0], piece[1]} : new int[]{pending[0], piece[1]};
            if (pending[1] - pending[0] >= minimum) {
                result.add(pending);
                pending = null;
            }
        }
        if (pending != null) {
            if (result.isEmpty()) result.add(pending);
            else result.get(result.size() - 1)[1] = pending[1];
        }
        return result;
    }
}
