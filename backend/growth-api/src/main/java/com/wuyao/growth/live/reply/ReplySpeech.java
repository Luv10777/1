package com.wuyao.growth.live.reply;

import java.util.List;

/**
 * Fixed phrases around an answer that cuts into narration. Nothing here is written by a model or
 * taken from a viewer.
 *
 * How a reply opens is chosen here, not left to the model: told to "say what was asked first", it
 * opens every reply with the same "有朋友问", which sounds like a machine. Most replies open on the
 * subject itself; only some are introduced as a viewer's question, and then not always in the same words.
 *
 * The lead-in is only a fallback: normally the model words the whole reply, opening included. It is
 * used when the merchant's saved answer has to be spoken as it stands. The bridge is what the host
 * says to get back to the narration after answering in the same voice.
 */
public final class ReplySpeech {
    /** A saved question longer than this is not read out; the lead-in only says a question came in. */
    static final int MAX_QUESTION_CHARS = 30;
    /** One reply in this many is introduced as a viewer's question; the rest open on the subject. */
    static final int ATTRIBUTED_ONE_IN = 3;
    private static final List<String> ATTRIBUTIONS = List.of(
            "有朋友问", "刚看到有人问", "弹幕里有朋友在问", "看到有人问", "有位朋友想知道");
    private static final List<String> SUBJECT_LEAD_INS = List.of("关于%s，", "说到%s，", "%s这个问题，");
    private static final String PLAIN_LEAD_IN = "回答一下弹幕里的问题，";
    private static final List<String> BRIDGES = List.of(
            "好，咱们接着说。", "好，回到刚才讲的。", "好，我们继续。", "那咱们接着往下讲。", "好，接着刚才的说。");

    private ReplySpeech() { }

    /**
     * How a reply that cuts into narration lets listeners know what it is about.
     *
     * @param attribution the words that introduce it as a viewer's question, or null to open on the subject itself
     */
    public record Opening(String attribution) {
        public static final Opening SUBJECT = new Opening(null);
    }

    /** @param seed anything stable for the comment, so a redraft or a retry opens the same way */
    public static Opening opening(String seed) {
        int hash = seed == null ? 0 : seed.hashCode();
        if (Math.floorMod(hash, ATTRIBUTED_ONE_IN) != 0) return Opening.SUBJECT;
        return new Opening(ATTRIBUTIONS.get(Math.floorMod(hash / ATTRIBUTED_ONE_IN, ATTRIBUTIONS.size())));
    }

    /**
     * @param savedQuestion the merchant's own wording of the question that was matched
     * @param seed          anything stable for the comment, so a retry says the same thing
     */
    public static String withLeadIn(String savedQuestion, String answer, String seed) {
        String question = savedQuestion == null ? "" : savedQuestion.strip().replaceAll("[\\p{P}\\s]+$", "").replaceAll("\\s+", " ");
        if (question.isEmpty() || question.length() > MAX_QUESTION_CHARS) return PLAIN_LEAD_IN + answer;
        Opening opening = opening(seed);
        String leadIn = opening.attribution() != null ? opening.attribution() + question + "，"
                : pick(SUBJECT_LEAD_INS, seed).formatted(question);
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
