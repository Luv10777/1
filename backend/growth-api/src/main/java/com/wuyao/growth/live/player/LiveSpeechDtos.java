package com.wuyao.growth.live.player;

import jakarta.validation.constraints.*;

public final class LiveSpeechDtos {
    private LiveSpeechDtos() {}
    public record SpeechRequest(@NotBlank @Size(max=100) String id,
                                @NotBlank @Pattern(regexp="APPEND|INTERRUPT|CLEAR_REPLAY") String mode,
                                @NotBlank @Size(max=1000) String text,
                                @Positive Long sampleId, @Size(max=120) String builtInVoice) {}
    /**
     * Synthesis runs in a worker, so the answer is an acknowledgement, not audio. status is the
     * item's state at the time of the call: PENDING for a fresh submission, or wherever an earlier
     * submission of the same id has got to.
     */
    public record SpeechResult(String id, String status, boolean deduplicated, Long durationMillis, String error) {}
    public record CommentRequest(@NotBlank @Size(max=100) String id, @NotBlank @Size(max=500) String text,
                                 @Positive Long sampleId, @Size(max=120) String builtInVoice) {}
    /**
     * What is known when a comment has been recorded. A new one is always "answering": the outcome
     * is decided by a worker and shows up in the comment feed. A comment delivered again reports
     * where the first delivery got to: answering, answered, skipped (nothing to respond to), attention
     * (left for a person) or unanswered.
     */
    public record CommentResult(String status, String answer, String source) {}
}
