package com.wuyao.growth.live.reply;

import java.util.List;

/**
 * Fixed phrases around an answer that cuts into narration. Nothing here is written by a model or
 * taken from a viewer.
 *
 * The lead-in is only a fallback: normally the model words the whole reply, lead-in included. It is
 * used when the merchant's saved answer has to be spoken as it stands. The bridge is what the host
 * says to get back to the narration after answering in the same voice.
 */
public final class ReplySpeech {
    /** A saved question longer than this is not read out; the lead-in only says a question came in. */
    static final int MAX_QUESTION_CHARS = 30;
    private static final List<String> LEAD_INS = List.of(
            "有朋友问%s，", "刚看到有朋友问%s，", "弹幕里有朋友在问%s，", "看到有人问%s，");
    private static final String PLAIN_LEAD_IN = "回答一下弹幕里的问题，";
    private static final List<String> BRIDGES = List.of(
            "好，咱们接着说。", "好，回到刚才讲的。", "好，我们继续。", "那咱们接着往下讲。", "好，接着刚才的说。");

    private ReplySpeech() { }

    /**
     * @param savedQuestion the merchant's own wording of the question that was matched
     * @param seed          anything stable for the comment, so a retry says the same thing
     */
    public static String withLeadIn(String savedQuestion, String answer, String seed) {
        String question = savedQuestion == null ? "" : savedQuestion.strip().replaceAll("[\\p{P}\\s]+$", "").replaceAll("\\s+", " ");
        String leadIn = question.isEmpty() || question.length() > MAX_QUESTION_CHARS
                ? PLAIN_LEAD_IN : pick(LEAD_INS, seed).formatted(question);
        return leadIn + answer;
    }

    /**
     * The line that hands back to narration, or null when none belongs there. A co-host who stepped
     * in says nothing more: the host's voice coming back is the hand-over, and announcing it every
     * time sounds like a switchboard. Whether the host's own line is spoken is decided at playback.
     */
    public static String bridge(String seed, boolean narrating, boolean byCohost) {
        return narrating && !byCohost ? pick(BRIDGES, seed) : null;
    }

    private static String pick(List<String> options, String seed) {
        return options.get(Math.floorMod(seed == null ? 0 : seed.hashCode(), options.size()));
    }
}
