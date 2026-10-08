package com.wuyao.growth.voice;

import java.util.List;
import java.util.function.Consumer;

/** Voice transport is kept separate from text/JSON model responses. Audio chunks are PCM16 mono. */
public interface VoiceProvider {
    String code();
    boolean configured();
    String model();
    default boolean supportsVoice(String voiceId) { return voiceId != null && !voiceId.isBlank(); }
    List<String> builtInVoices();
    /** Local validation only; must not create a voice or perform provider requests. */
    default void validateSampleUrl(String sampleUrl) { }
    String createVoice(String sampleUrl, String stablePrefix);
    String voiceStatus(String voiceId);
    void deleteVoice(String voiceId);
    Audio synthesize(String text, String voiceId, Consumer<byte[]> onChunk);
    /**
     * One unit of speech and when it is heard, measured from the start of the clip. The text is what
     * was actually spoken after the provider's own normalisation ("9.9" becomes "九点九"), and
     * punctuation is included: its span is the pause it stands for.
     */
    record Word(String text, long beginMillis, long endMillis) { }

    /** @param words in spoken order; empty when the provider reports no timing */
    record Audio(byte[] pcm, int sampleRate, long firstAudioMillis, List<Word> words) {
        public Audio(byte[] pcm, int sampleRate, long firstAudioMillis) {
            this(pcm, sampleRate, firstAudioMillis, List.of());
        }
    }
}
