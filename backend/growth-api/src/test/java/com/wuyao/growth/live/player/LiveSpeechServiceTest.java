package com.wuyao.growth.live.player;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.speech.*;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.voice.VoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveSpeechServiceTest {
    private final LiveSessionRepository sessions = mock(LiveSessionRepository.class);
    private final LiveSpeechItemRepository items = mock(LiveSpeechItemRepository.class);
    private final StoreAccessService stores = mock(StoreAccessService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final VoiceService voices = mock(VoiceService.class);
    private final LiveSpeechQueue queue = mock(LiveSpeechQueue.class);
    private final LiveSpeechAudioStore audio = mock(LiveSpeechAudioStore.class);
    private final TaskService tasks = mock(TaskService.class);
    private final LiveSpeechService service = new LiveSpeechService(sessions, items, stores, tx, voices, queue, audio, tasks);
    private final LiveSpeechDtos.SpeechRequest request =
            new LiveSpeechDtos.SpeechRequest("speech-1", "APPEND", "你好", null, "voice");
    private LiveSpeechItem stored;

    @BeforeEach
    void setup() {
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        LiveSession session = new LiveSession();
        session.setId(2L); session.setStoreId(4L); session.setTenantId(1L);
        when(sessions.findForUpdate(2L)).thenReturn(Optional.of(session));
        when(items.findBySessionIdAndCommandId(2L, "speech-1")).thenAnswer(call -> Optional.ofNullable(stored));
        when(queue.create(eq(1L), eq(2L), any())).thenAnswer(call -> {
            LiveSpeechQueue.Draft draft = call.getArgument(2);
            stored = new LiveSpeechItem();
            stored.setId(9L);
            stored.setCommandId(draft.commandId());
            stored.setStatus(draft.status());
            stored.setFingerprint(draft.fingerprint());
            return new LiveSpeechQueue.Created(stored, true);
        });
    }

    @Test
    void submissionIsRecordedAndHandedToAWorkerWithoutSynthesisingInTheRequest() {
        var result = service.speak(2L, request, 3L);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.deduplicated()).isFalse();
        verify(queue).create(eq(1L), eq(2L), argThat(draft -> "MANUAL".equals(draft.kind())
                && "builtin:voice".equals(draft.voice()) && "你好".equals(draft.text()) && Long.valueOf(3L).equals(draft.createdBy())));
        verify(tasks).submit(LiveSpeechSynthesizeHandler.TYPE, "LIVE", Map.of("itemId", 9L), "live-speech:9", 3L);
        verify(voices, never()).synthesizeAudio(any(), any(), any());
    }

    @Test
    void aReplyToAViewerIsNotQueuedBehindNarration() {
        service.submit(2L, LiveSpeechItem.REPLY, request, "好，咱们接着说。", 3L);
        verify(tasks).submit(LiveSpeechSynthesizeHandler.TYPE, "LIVE_REPLY", Map.of("itemId", 9L), "live-speech:9", 3L);
    }

    @Test
    void retryIsDeduplicatedBeforePaidSynthesisAndChangedContentIsRejected() {
        assertThat(service.speak(2L, request, 3L).deduplicated()).isFalse();
        assertThat(service.speak(2L, request, 3L).deduplicated()).isTrue();
        assertThatThrownBy(() -> service.speak(2L,
                new LiveSpeechDtos.SpeechRequest("speech-1", "APPEND", "不同内容", null, "voice"), 3L))
                .isInstanceOf(BizException.class).hasMessageContaining("不同内容");
        verify(queue, times(1)).create(any(), any(), any());
        verify(tasks, times(1)).submit(any(), any(), any(), any(), any());
    }

    @Test
    void failedSynthesisRequiresAnExplicitNewIdAndIsNeverChargedAgainAutomatically() {
        service.speak(2L, request, 3L);
        stored.setStatus(LiveSpeechItem.FAILED);
        stored.setErrorMessage("语音供应商请求未完成");

        var retried = service.speak(2L, request, 3L);

        assertThat(retried.status()).isEqualTo("FAILED");
        assertThat(retried.deduplicated()).isTrue();
        assertThat(retried.error()).contains("未完成");
        verify(tasks, times(1)).submit(any(), any(), any(), any(), any());
    }

    @Test
    void unusableVoiceIsRejectedWhileTheUserIsWaitingAndNothingIsQueued() {
        when(voices.resolveVoice(4L, null, "voice", 3L))
                .thenThrow(BizException.of(ErrorCode.BAD_REQUEST, "请选择可用的系统音色"));
        assertThatThrownBy(() -> service.speak(2L, request, 3L)).hasMessageContaining("系统音色");
        verifyNoInteractions(queue, tasks);
    }

    @Test
    void clipIsServedOnlyToThePlayerOfItsOwnSessionAndOnlyWhileQueued() {
        LiveSpeechItem ready = new LiveSpeechItem();
        ready.setSessionId(2L); ready.setStatus(LiveSpeechItem.READY); ready.setAudioKey("t1/live-speech/2/9.wav");
        when(items.findById(9L)).thenReturn(Optional.of(ready));
        when(audio.get("t1/live-speech/2/9.wav")).thenReturn(new byte[]{1});

        assertThat(service.audio(new LivePlayerDtos.Scope(1L, 2L, 5L, "本场"), "9")).containsExactly(1);
        assertThat(TenantContext.get()).isNull();
        assertThatThrownBy(() -> service.audio(new LivePlayerDtos.Scope(1L, 77L, 5L, "别的场次"), "9"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.audio(new LivePlayerDtos.Scope(1L, 2L, 5L, "本场"), "../secret"))
                .isInstanceOf(BizException.class);
        ready.setStatus(LiveSpeechItem.PLAYED);
        assertThatThrownBy(() -> service.audio(new LivePlayerDtos.Scope(1L, 2L, 5L, "本场"), "9"))
                .isInstanceOf(BizException.class);
    }
}
