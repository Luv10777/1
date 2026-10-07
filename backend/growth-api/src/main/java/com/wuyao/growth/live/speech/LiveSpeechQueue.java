package com.wuyao.growth.live.speech;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.wuyao.growth.live.speech.LiveSpeechItem.*;

/** State transitions of the persisted playback queue. Every method is one short transaction. */
@Service
@RequiredArgsConstructor
public class LiveSpeechQueue {
    /** A clip nobody played within this window is no longer worth playing. */
    public static final Duration RETENTION = Duration.ofHours(2);
    public static final List<String> UNPLAYED = List.of(GENERATING, PENDING, READY);
    static final int MAX_UNPLAYED = 200;

    private final LiveSpeechItemRepository items;
    private final LiveSpeechAudioStore audio;
    private final LiveSpeechNotifier notifier;

    public record Draft(String commandId, String kind, String mode, String status, String text, String voice,
                        Integer seq, Long productId, String beat, String fingerprint, Long createdBy) { }

    public record Created(LiveSpeechItem item, boolean created) { }

    /**
     * The caller must hold the session row lock: that is what makes the look-up-then-insert below
     * safe against a concurrent submission of the same command.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Created create(Long tenantId, Long sessionId, Draft draft) {
        Optional<LiveSpeechItem> existing = items.findBySessionIdAndCommandId(sessionId, draft.commandId());
        if (existing.isPresent()) return new Created(existing.get(), false);
        if ("CLEAR_REPLAY".equals(draft.mode())) {
            // Everything submitted earlier is superseded, including clips still being synthesised.
            discardUnplayed(sessionId);
        } else if (unplayed(sessionId) >= MAX_UNPLAYED) {
            throw BizException.of(ErrorCode.CONFLICT, "播报队列已满，请等待播放或清空队列");
        }
        LiveSpeechItem item = new LiveSpeechItem();
        item.setTenantId(tenantId);
        item.setSessionId(sessionId);
        item.setCommandId(draft.commandId());
        item.setKind(draft.kind());
        item.setMode(draft.mode());
        item.setStatus(draft.status());
        item.setText(draft.text() == null ? "" : draft.text());
        item.setVoice(draft.voice());
        item.setSeq(draft.seq());
        item.setProductId(draft.productId());
        item.setBeat(draft.beat());
        item.setFingerprint(draft.fingerprint());
        item.setCreatedBy(draft.createdBy());
        item.setQueuedAt(item.getCreatedAt());
        if (READY.equals(draft.status())) item.setReadyAt(Instant.now());
        item = items.saveAndFlush(item);
        if (READY.equals(draft.status())) notifier.ready(tenantId, sessionId);
        return new Created(item, true);
    }

    /** Script text arrives after the row exists; only a row still waiting for it may take it. */
    @Transactional
    public boolean attachText(Long itemId, String text, String fingerprint) {
        LiveSpeechItem item = items.findForUpdate(itemId).orElse(null);
        if (item == null || !GENERATING.equals(item.getStatus())) return false;
        item.setText(text);
        item.setFingerprint(fingerprint);
        item.setStatus(PENDING);
        // Writing is done; it now waits for a worker to synthesise it.
        item.setQueuedAt(Instant.now());
        item.setStartedAt(null);
        return true;
    }

    /**
     * Records that a worker has taken the item, so that slow progress can be told apart from no
     * worker running at all. Call it in the short transaction before the provider request.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markStarted(LiveSpeechItem item) {
        item.setStartedAt(Instant.now());
    }

    /** @return false when the item was discarded or failed while its audio was being produced */
    @Transactional
    public boolean markReady(Long itemId, String audioKey, long durationMillis, List<Long> pauseOffsets,
                             long firstAudioMillis, Long outroOffsetMillis) {
        LiveSpeechItem item = items.findForUpdate(itemId).orElse(null);
        if (item == null || !PENDING.equals(item.getStatus())) return false;
        item.setAudioKey(audioKey);
        item.setDurationMillis(durationMillis);
        item.setPauseOffsets(pauseOffsets);
        item.setFirstAudioMillis(firstAudioMillis);
        item.setOutroOffsetMillis(outroOffsetMillis);
        item.setStatus(READY);
        item.setReadyAt(Instant.now());
        notifier.ready(item.getTenantId(), item.getSessionId());
        return true;
    }

    @Transactional
    public boolean markFailed(Long itemId, String message) {
        return finish(itemId, FAILED, message, List.of(GENERATING, PENDING));
    }

    @Transactional
    public boolean discard(Long itemId) {
        return finish(itemId, DISCARDED, null, UNPLAYED);
    }

    private boolean finish(Long itemId, String status, String message, List<String> from) {
        LiveSpeechItem item = items.findForUpdate(itemId).orElse(null);
        if (item == null || !from.contains(item.getStatus())) return false;
        close(item, status, message);
        return true;
    }

    @Transactional(readOnly = true)
    public List<LiveSpeechItem> deliverable(Long sessionId) {
        return items.findBySessionIdAndStatusAndReadyAtAfterOrderByIdAsc(sessionId, READY, Instant.now().minus(RETENTION));
    }

    /** The player finished (or dropped) a clip. Only a READY item can be acknowledged. */
    @Transactional
    public Optional<LiveSpeechItem> acknowledge(Long sessionId, String commandId) {
        Optional<LiveSpeechItem> item = items.findForUpdate(sessionId, commandId)
                .filter(found -> READY.equals(found.getStatus()));
        item.ifPresent(found -> close(found, PLAYED, null));
        return item;
    }

    @Transactional
    public int discardUnplayed(Long sessionId) {
        List<LiveSpeechItem> pending = items.findBySessionIdAndStatusIn(sessionId, UNPLAYED);
        pending.forEach(item -> close(item, DISCARDED, null));
        items.flush();
        return pending.size();
    }

    @Transactional(readOnly = true)
    public long unplayed(Long sessionId) {
        return items.countBySessionIdAndStatusInAndCreatedAtAfter(sessionId, UNPLAYED, Instant.now().minus(RETENTION));
    }

    private void close(LiveSpeechItem item, String status, String message) {
        item.setStatus(status);
        item.setFinishedAt(Instant.now());
        if (message != null) item.setErrorMessage(message.length() > 500 ? message.substring(0, 500) : message);
        String key = item.getAudioKey();
        item.setAudioKey(null);
        if (key != null) afterCommit(() -> audio.delete(key));
    }

    /** Storage is touched only once the database has decided the clip is gone. */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
