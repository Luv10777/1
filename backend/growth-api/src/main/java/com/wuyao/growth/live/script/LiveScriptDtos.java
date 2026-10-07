package com.wuyao.growth.live.script;

import com.wuyao.growth.live.speech.LiveSpeechItem;

import java.time.Instant;

public final class LiveScriptDtos {
    private LiveScriptDtos() { }

    /**
     * @param buffered segments synthesised and waiting to be played
     * @param inFlight segments being written or synthesised
     * @param hint     set when work is taking long, and says whether it is waiting for a worker or for a provider
     */
    public record Status(boolean enabled, String sessionStatus, int targetBuffer, long buffered, long inFlight,
                         int generated, int failures, String lastError, String hint) { }

    public record ItemView(Long id, String commandId, String kind, String mode, String status, String text,
                           String voice, Long productId, String beat, Long durationMillis, String error,
                           Instant createdAt, Instant readyAt, Instant finishedAt) {
        static ItemView of(LiveSpeechItem item) {
            ScriptPlan.Beat beat = item.getBeat() == null ? null : ScriptPlan.beat(item.getBeat());
            return new ItemView(item.getId(), item.getCommandId(), item.getKind(), item.getMode(), item.getStatus(),
                    item.getText(), item.getVoice(), item.getProductId(), beat == null ? null : beat.label(),
                    item.getDurationMillis(), item.getErrorMessage(), item.getCreatedAt(), item.getReadyAt(),
                    item.getFinishedAt());
        }
    }
}
