package com.wuyao.growth.live.player;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.reply.LiveReplyLane;
import com.wuyao.growth.live.speech.*;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.voice.VoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Accepts speech for a session. The api only records the utterance and submits a task in the same
 * transaction; a worker synthesises it and the player receives it once it is READY.
 */
@Service
@RequiredArgsConstructor
public class LiveSpeechService {
    private static final Set<String> ACTIVE = Set.of("DRAFT", "LIVE", "PAUSED");
    private final LiveSessionRepository sessions;
    private final LiveSpeechItemRepository items;
    private final StoreAccessService stores;
    private final TransactionTemplate transactions;
    private final VoiceService voices;
    private final LiveSpeechQueue queue;
    private final LiveSpeechAudioStore audio;
    private final TaskService tasks;

    public LiveSpeechDtos.SpeechResult speak(Long sessionId, LiveSpeechDtos.SpeechRequest request, Long userId) {
        return submit(sessionId, LiveSpeechItem.MANUAL, request, userId);
    }

    public LiveSpeechDtos.SpeechResult submit(Long sessionId, String kind, LiveSpeechDtos.SpeechRequest request, Long userId) {
        return submit(sessionId, kind, request, null, userId);
    }

    /** @param outro a closing line synthesised with the speech but spoken only if narration resumes after it */
    public LiveSpeechDtos.SpeechResult submit(Long sessionId, String kind, LiveSpeechDtos.SpeechRequest request,
                                              String outro, Long userId) {
        if (request.id() == null || request.id().isBlank() || request.id().length() > 100
                || request.text() == null || request.text().isBlank() || request.text().length() > 1000
                || request.mode() == null || !Set.of("APPEND", "INTERRUPT", "CLEAR_REPLAY").contains(request.mode())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "播报请求不合法");
        }
        String voice = LiveVoice.of(request.sampleId(), request.builtInVoice()).encode();
        String fingerprint = PlayerTokenService.sha256(request.mode() + "\n" + request.text() + "\n" + voice);
        return transactions.execute(tx -> {
            LiveSession session = sessions.findForUpdate(sessionId)
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "直播场次不存在"));
            stores.requireAccess(session.getStoreId(), userId);
            if (!ACTIVE.contains(session.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT, "该场次已结束，不能继续合成播报");
            }
            // A repeated id never buys a second synthesis, whatever became of the first.
            Optional<LiveSpeechItem> existing = items.findBySessionIdAndCommandId(sessionId, request.id());
            if (existing.isPresent()) {
                if (!fingerprint.equals(existing.get().getFingerprint())) {
                    throw BizException.of(ErrorCode.CONFLICT, "同一播报 ID 不能用于不同内容");
                }
                return result(existing.get(), true);
            }
            // Fail here, where the user is waiting, rather than later inside the worker.
            voices.resolveVoice(session.getStoreId(), request.sampleId(), request.builtInVoice(), userId);
            LiveSpeechItem item = queue.create(session.getTenantId(), sessionId, new LiveSpeechQueue.Draft(request.id(),
                    kind, request.mode(), LiveSpeechItem.PENDING, request.text(), voice, null, null, null,
                    fingerprint, userId)).item();
            item.setOutroText(outro);
            // A reply's clip is normally synthesised at once by whoever worded it; this task is what
            // makes sure it still happens if that worker dies, and it must not sit behind narration.
            tasks.submit(LiveSpeechSynthesizeHandler.TYPE,
                    LiveSpeechItem.REPLY.equals(kind) ? LiveReplyLane.QUEUE : LiveSpeechSynthesizeHandler.QUEUE,
                    Map.of("itemId", item.getId()), "live-speech:" + item.getId(), userId);
            return result(item, false);
        });
    }

    /** Clip bytes for the player bound to this session; nothing else may read them. */
    public byte[] audio(LivePlayerDtos.Scope scope, String id) {
        Long itemId;
        try {
            itemId = Long.valueOf(id);
        } catch (NumberFormatException e) {
            throw missingAudio();
        }
        String key = TenantContext.runAs(scope.tenantId(), () -> transactions.execute(tx -> items.findById(itemId)
                .filter(item -> scope.sessionId().equals(item.getSessionId())
                        && LiveSpeechItem.READY.equals(item.getStatus()) && item.getAudioKey() != null)
                .map(LiveSpeechItem::getAudioKey).orElseThrow(LiveSpeechService::missingAudio)));
        return audio.get(key);
    }

    private static BizException missingAudio() {
        return BizException.of(ErrorCode.NOT_FOUND, "播报音频不存在或已过期，请重新生成");
    }

    private static LiveSpeechDtos.SpeechResult result(LiveSpeechItem item, boolean deduplicated) {
        return new LiveSpeechDtos.SpeechResult(item.getCommandId(), item.getStatus(), deduplicated,
                item.getDurationMillis(), item.getErrorMessage());
    }
}
