package com.wuyao.growth.live.speech;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.audio.PcmAudio;
import com.wuyao.growth.voice.VoiceDtos;
import com.wuyao.growth.voice.VoiceProvider;
import com.wuyao.growth.voice.VoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one queued utterance into audio. The provider call is paid and live speech goes stale fast,
 * so a failure marks the item FAILED instead of asking the task framework to retry it: whoever
 * queued it decides whether to say it again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveSpeechSynthesizeHandler implements TaskHandler {
    public static final String TYPE = "LIVE_SPEECH_SYNTHESIZE";
    /** Dedicated queue so a worker process can be reserved for live rooms. */
    public static final String QUEUE = "LIVE";
    /** Also bounds a re-run after a crashed worker's lease expires: it finds the item too old to matter. */
    static final Duration MAX_AGE = Duration.ofMinutes(10);
    private static final Set<String> ACTIVE = Set.of("DRAFT", "LIVE", "PAUSED");

    private final LiveSpeechItemRepository items;
    private final LiveSessionRepository sessions;
    private final LiveSpeechQueue queue;
    private final LiveSpeechAudioStore store;
    private final VoiceService voices;
    private final LiveSpeechDelivery delivery;
    private final List<LiveSpeechOutcomeListener> listeners;
    private final TransactionTemplate transactions;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> handle(Task task) {
        return synthesize(((Number) task.getPayload().get("itemId")).longValue());
    }

    /**
     * Synthesises the item now, on the caller's thread. Whoever has just worded an utterance can
     * call this instead of waiting for the queued task to come round; that task then finds the
     * item taken and leaves it alone.
     */
    public Map<String, Object> synthesize(Long itemId) {
        Job job = transactions.execute(tx -> prepare(itemId));
        if (job == null) return result(itemId, "SKIPPED", null);
        if (job.rejection() != null) {
            notifyListeners(job, false, job.failed() ? job.rejection() : null);
            return result(itemId, job.failed() ? LiveSpeechItem.FAILED : LiveSpeechItem.DISCARDED, job.rejection());
        }

        boolean ready = false;
        String error = null;
        try {
            // No transaction is open here: the provider may take many seconds.
            Clip clip = synthesize(job);
            String key = store.key(job.tenantId(), job.sessionId(), itemId);
            store.put(key, PcmAudio.wav(clip.pcm(), clip.sampleRate()));
            ready = queue.markReady(itemId, key, PcmAudio.durationMillis(clip.pcm(), clip.sampleRate()),
                    clip.boundaries(), clip.firstAudioMillis(), clip.outroOffsetMillis());
            // Discarded while we were synthesising (session ended, queue cleared, pairing revoked).
            if (!ready) store.delete(key);
        } catch (RuntimeException e) {
            error = e instanceof BizException ? e.getMessage() : "语音合成未完成，请稍后重试";
            if (!(e instanceof BizException)) log.warn("直播语音合成异常: itemId={}", itemId, e);
            queue.markFailed(itemId, error);
        }
        if (ready) delivery.deliver(job.tenantId(), job.sessionId());
        notifyListeners(job, ready, error);
        return result(itemId, ready ? LiveSpeechItem.READY : error == null ? LiveSpeechItem.DISCARDED : LiveSpeechItem.FAILED, error);
    }

    /** A cut is only made where the audio really dips; louder than this and the speaker has not paused. */
    static final double PAUSE_RMS = 0.02;
    /** The provider's times are good to about one syllable, so the samples around them are searched too. */
    private static final long SLACK_MILLIS = 80;

    /**
     * Speaks the whole text in one request and works out where each sentence starts from the timing
     * that comes back with it. Those are the places playback may be interrupted. Sentence by
     * sentence synthesis would give the same places, but every request costs a couple of seconds
     * before its first sample, which made a segment several times slower to prepare.
     */
    private Clip synthesize(Job job) {
        boolean interruptible = !"INTERRUPT".equals(job.mode());
        VoiceProvider.Audio audio = voices.synthesizeAudio(job.storeId(), new VoiceDtos.SpeechRequest(
                SpeechParts.spoken(job.text(), job.outro()), job.voice().sampleId(), job.voice().builtInVoice()), job.userId());
        int sampleRate = audio.sampleRate();
        // The provider pads the clip with silence of its own; the pauses between clips are the player's to set.
        int[] kept = PcmAudio.speechRange(audio.pcm(), sampleRate);
        byte[] pcm = kept == null ? audio.pcm() : java.util.Arrays.copyOfRange(audio.pcm(), kept[0] * 2, kept[1] * 2);
        long lead = kept == null ? 0 : kept[0] * 1000L / sampleRate;
        long duration = PcmAudio.durationMillis(pcm, sampleRate);

        SpeechTimeline timeline = new SpeechTimeline(audio.words());
        if (timeline.isEmpty()) {
            // No timing came back: fall back to pauses found in the audio alone, wherever they fall.
            return new Clip(pcm, sampleRate, interruptible ? PcmAudio.quietPoints(pcm, sampleRate) : List.of(), null,
                    audio.firstAudioMillis());
        }
        int closing = timeline.startOfClosing(job.outro());
        List<Long> boundaries = new ArrayList<>();
        if (interruptible) {
            String narration = closing < 0 ? timeline.text() : timeline.text().substring(0, closing);
            for (int start : SpeechParts.starts(narration)) {
                Long at = pauseAt(timeline, start, audio, lead, duration);
                if (at != null && (boundaries.isEmpty() || at > boundaries.get(boundaries.size() - 1))) boundaries.add(at);
            }
        }
        return new Clip(pcm, sampleRate, List.copyOf(boundaries),
                closing < 0 ? null : pauseAt(timeline, closing, audio, lead, duration), audio.firstAudioMillis());
    }

    /** @return the quietest moment between the word at this position and what precedes it, or null if the speaker never lets up there */
    private static Long pauseAt(SpeechTimeline timeline, int offset, VoiceProvider.Audio audio, long lead, long duration) {
        long[] pause = timeline.pauseBefore(offset);
        if (pause == null) return null;
        PcmAudio.Dip dip = PcmAudio.quietest(audio.pcm(), audio.sampleRate(), pause[0] - SLACK_MILLIS, pause[1] + SLACK_MILLIS);
        if (dip == null || dip.rms() >= PAUSE_RMS) return null;
        long at = dip.atMillis() - lead;
        return at > 0 && at < duration ? at : null;
    }

    private Job prepare(Long itemId) {
        LiveSpeechItem item = items.findForUpdate(itemId).orElse(null);
        if (item == null || !LiveSpeechItem.PENDING.equals(item.getStatus())) return null;
        // Taken under the row lock: of two workers reaching for the same item, one synthesises it and
        // the other leaves. Without this both would pay for the clip, and the loser would then delete
        // the audio the winner had just stored. A worker that died holding it is not waited for: by
        // the time its task is retried the item is too old to be worth speaking, and fails below.
        if (item.getStartedAt() != null && item.getStartedAt().isAfter(Instant.now().minus(MAX_AGE))) return null;
        LiveSession session = sessions.findById(item.getSessionId()).orElse(null);
        LiveVoice voice = LiveVoice.parse(item.getVoice());
        String rejection = null;
        boolean failed = false;
        if (session == null || !ACTIVE.contains(session.getStatus())) {
            rejection = "场次已结束";
            queue.discard(itemId);
        } else if (item.getCreatedAt().isBefore(Instant.now().minus(MAX_AGE))) {
            rejection = "等待合成超时，已跳过";
            failed = queue.markFailed(itemId, rejection);
        } else if (voice == null) {
            rejection = "播报音色无效，请重新选择音色";
            failed = queue.markFailed(itemId, rejection);
        } else {
            queue.markStarted(item);
        }
        return new Job(item.getTenantId(), item.getSessionId(), session == null ? null : session.getStoreId(),
                item.getKind(), item.getMode(), item.getText(), item.getOutroText(), voice, item.getCreatedBy(),
                rejection, failed);
    }

    private void notifyListeners(Job job, boolean ready, String error) {
        for (LiveSpeechOutcomeListener listener : listeners) {
            try {
                listener.speechFinished(job.sessionId(), job.kind(), ready, error);
            } catch (RuntimeException e) {
                // The clip itself is settled; a follow-up failing must not fail (and retry) the task.
                log.warn("直播播报后续处理失败: sessionId={}", job.sessionId(), e);
            }
        }
    }

    private static Map<String, Object> result(Long itemId, String status, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", itemId);
        result.put("status", status);
        if (error != null) result.put("error", error);
        return result;
    }

    private record Job(Long tenantId, Long sessionId, Long storeId, String kind, String mode, String text,
                       String outro, LiveVoice voice, Long userId, String rejection, boolean failed) { }

    private record Clip(byte[] pcm, int sampleRate, List<Long> boundaries, Long outroOffsetMillis,
                        long firstAudioMillis) { }
}
