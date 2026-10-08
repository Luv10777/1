package com.wuyao.growth.live.speech;

/** Lets a producer of speech react once one of its utterances has left the synthesis stage. */
public interface LiveSpeechOutcomeListener {
    /**
     * @param ready true when the clip is queued for playback
     * @param error why it is not, or null when it was merely discarded
     */
    void speechFinished(Long sessionId, String kind, boolean ready, String error);
}
