package com.wuyao.growth.live.speech;

import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.voice.VoiceProvider;
import com.wuyao.growth.voice.VoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveSpeechSynthesizeHandlerTest {
    private final LiveSpeechItemRepository items = mock(LiveSpeechItemRepository.class);
    private final LiveSessionRepository sessions = mock(LiveSessionRepository.class);
    private final LiveSpeechQueue queue = mock(LiveSpeechQueue.class);
    private final LiveSpeechAudioStore store = mock(LiveSpeechAudioStore.class);
    private final VoiceService voices = mock(VoiceService.class);
    private final LiveSpeechDelivery delivery = mock(LiveSpeechDelivery.class);
    private final LiveSpeechOutcomeListener listener = mock(LiveSpeechOutcomeListener.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final LiveSpeechSynthesizeHandler handler = new LiveSpeechSynthesizeHandler(
            items, sessions, queue, store, voices, delivery, List.of(listener), tx);
    private final LiveSpeechItem item = new LiveSpeechItem();
    private final LiveSession session = new LiveSession();
    private final Task task = new Task();

    @BeforeEach
    void setup() {
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        item.setId(9L); item.setTenantId(1L); item.setSessionId(2L); item.setKind(LiveSpeechItem.SCRIPT);
        item.setMode("APPEND"); item.setStatus(LiveSpeechItem.PENDING); item.setText("这款蜂蜜口感清甜"); item.setVoice("sample:7"); item.setCreatedBy(3L);
        session.setId(2L); session.setStoreId(4L); session.setStatus("LIVE");
        when(items.findForUpdate(9L)).thenReturn(Optional.of(item));
        when(sessions.findById(2L)).thenReturn(Optional.of(session));
        when(store.key(1L, 2L, 9L)).thenReturn("t1/live-speech/2/9.wav");
        when(voices.synthesizeAudio(eq(4L), any(), eq(3L))).thenReturn(new VoiceProvider.Audio(new byte[48000], 24000, 120));
        task.setPayload(Map.of("itemId", 9));
    }

    @Test
    void synthesisedClipIsStoredQueuedPushedAndReportedToItsProducer() {
        when(queue.markReady(eq(9L), any(), anyLong(), any(), anyLong(), any())).thenReturn(true);

        assertThat(handler.handle(task)).containsEntry("status", "READY");

        verify(voices).synthesizeAudio(eq(4L), argThat(request -> Long.valueOf(7L).equals(request.sampleId())
                && request.builtInVoice() == null && "这款蜂蜜口感清甜".equals(request.text())), eq(3L));
        verify(store).put(eq("t1/live-speech/2/9.wav"), argThat(wav -> wav.length == 48044));
        verify(queue).markReady(eq(9L), eq("t1/live-speech/2/9.wav"), eq(1000L), any(), eq(120L), isNull());
        verify(delivery).deliver(1L, 2L);
        verify(listener).speechFinished(2L, LiveSpeechItem.SCRIPT, true, null);
    }

    /** Speech from 500 ms on, 100 ms a character, with real silence wherever a sentence ends. */
    private static VoiceProvider.Audio spoken(String text) {
        byte[] pcm = new byte[(500 + text.length() * 100 + 1000) * 48];
        List<VoiceProvider.Word> words = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            long begin = 500 + i * 100L;
            String character = text.substring(i, i + 1);
            words.add(new VoiceProvider.Word(character, begin, begin + 100));
            if ("。！？".contains(character)) continue;
            for (int sample = (int) begin * 24; sample < (begin + 100) * 24; sample++) pcm[2 * sample + 1] = (byte) (sample % 2 == 0 ? 40 : -40);
        }
        return new VoiceProvider.Audio(pcm, 24000, 120, words);
    }

    @Test
    @SuppressWarnings("unchecked")
    void narrationIsSpokenInOneRequestAndMayOnlyBeInterruptedWhereASentenceStarts() {
        String text = "这款蜂蜜口感清甜细腻。一罐是五百克的量。喜欢的朋友可以看看。";
        item.setText(text);
        when(voices.synthesizeAudio(eq(4L), any(), eq(3L))).thenReturn(spoken(text));
        when(queue.markReady(eq(9L), any(), anyLong(), any(), anyLong(), any())).thenReturn(true);

        assertThat(handler.handle(task)).containsEntry("status", "READY");

        // One request for the whole segment, however many sentences it has.
        verify(voices, times(1)).synthesizeAudio(eq(4L), argThat(request -> text.equals(request.text())), eq(3L));
        var boundaries = org.mockito.ArgumentCaptor.forClass(List.class);
        var duration = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(queue).markReady(eq(9L), any(), duration.capture(), boundaries.capture(), eq(120L), isNull());
        // The provider's half second of leading silence and its tail are gone: 30 characters of speech
        // less the final full stop, with the 30 + 40 ms margins.
        assertThat(duration.getValue()).isEqualTo(2900 + 70);
        // Sentences start at characters 11 and 20; each cut falls in the silence of the full stop before it.
        List<Long> points = boundaries.getValue();
        assertThat(points).hasSize(2);
        assertThat(points.get(0)).isBetween(1030L, 1130L);
        assertThat(points.get(1)).isBetween(1930L, 2030L);
    }

    @Test
    void aReplyIsNeverInterruptedAndItsClosingLineStartsWhereTheTimingSaysItDoes() {
        item.setKind(LiveSpeechItem.REPLY); item.setMode("INTERRUPT");
        item.setText("有朋友问几点关门，我们每天晚上九点关门。周末也一样"); item.setOutroText("好，咱们接着说。");
        String sent = "有朋友问几点关门，我们每天晚上九点关门。周末也一样。好，咱们接着说。";
        when(voices.synthesizeAudio(eq(4L), any(), eq(3L))).thenReturn(spoken(sent));
        when(queue.markReady(eq(9L), any(), anyLong(), any(), anyLong(), any())).thenReturn(true);

        assertThat(handler.handle(task)).containsEntry("status", "READY");

        // Answer and closing line go out together, the answer closed with a full stop first.
        verify(voices, times(1)).synthesizeAudio(eq(4L), argThat(request -> sent.equals(request.text())), eq(3L));
        var outro = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(queue).markReady(eq(9L), any(), anyLong(), eq(List.of()), eq(120L), outro.capture());
        // “好” is character 27: the full stop before it is silent from 2530 to 2630 ms after the trimmed start.
        assertThat(outro.getValue()).isBetween(2530L, 2630L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSentenceStartTheSpeakerRunsStraightThroughIsNotACutAndNoTimingFallsBackToPausesInTheAudio() {
        String text = "这款蜂蜜口感清甜细腻。一罐是五百克的量。喜欢的朋友可以看看。";
        item.setText(text);
        VoiceProvider.Audio audio = spoken(text);
        // The first full stop is spoken through without a pause.
        for (int sample = 1500 * 24; sample < 1600 * 24; sample++) audio.pcm()[2 * sample + 1] = (byte) (sample % 2 == 0 ? 40 : -40);
        when(voices.synthesizeAudio(eq(4L), any(), eq(3L))).thenReturn(audio,
                new VoiceProvider.Audio(audio.pcm(), 24000, 120));
        when(queue.markReady(eq(9L), any(), anyLong(), any(), anyLong(), any())).thenReturn(true);
        var boundaries = org.mockito.ArgumentCaptor.forClass(List.class);

        handler.handle(task);
        verify(queue).markReady(eq(9L), any(), anyLong(), boundaries.capture(), anyLong(), isNull());
        assertThat((List<Long>) boundaries.getValue()).hasSize(1).allMatch(point -> point > 1900);

        // A provider that reports no timing still yields a clip; its pauses are then found by level alone.
        item.setStatus(LiveSpeechItem.PENDING);
        handler.handle(task);
        verify(queue, times(2)).markReady(eq(9L), any(), anyLong(), any(), anyLong(), isNull());
    }

    @Test
    void aClosingLineWhoseStartCannotBeFoundIsSimplyPlayedThroughWithTheRest() {
        item.setKind(LiveSpeechItem.REPLY); item.setMode("INTERRUPT");
        item.setText("我们每天晚上九点关门。"); item.setOutroText("好，咱们接着说。");
        // No timing at all.
        when(queue.markReady(eq(9L), any(), anyLong(), any(), anyLong(), any())).thenReturn(true);
        assertThat(handler.handle(task)).containsEntry("status", "READY");
        verify(voices).synthesizeAudio(eq(4L), argThat(request -> "我们每天晚上九点关门。好，咱们接着说。".equals(request.text())), eq(3L));
        verify(queue).markReady(eq(9L), any(), anyLong(), eq(List.of()), eq(120L), isNull());
    }

    @Test
    void providerFailureMarksTheItemFailedInsteadOfAskingTheFrameworkToRetryAPaidCall() {
        when(voices.synthesizeAudio(eq(4L), any(), eq(3L)))
                .thenThrow(BizException.of(ErrorCode.BAD_REQUEST, "语音供应商请求未完成"));

        assertThat(handler.handle(task)).containsEntry("status", "FAILED");

        verify(queue).markFailed(9L, "语音供应商请求未完成");
        verify(listener).speechFinished(2L, LiveSpeechItem.SCRIPT, false, "语音供应商请求未完成");
        verifyNoInteractions(delivery, store);
    }

    @Test
    void anItemThatIsNoLongerPendingIsLeftAloneSoARerunCannotSynthesiseTwice() {
        item.setStatus(LiveSpeechItem.READY);
        assertThat(handler.handle(task)).containsEntry("status", "SKIPPED");
        verifyNoInteractions(voices, store, delivery, listener);
    }

    @Test
    void anItemAnotherWorkerHasJustTakenIsLeftToItSoTheClipIsNeverPaidForOrStoredTwice() {
        // Whoever worded a reply synthesises it at once; the task queued for it comes round a moment later.
        item.setStartedAt(Instant.now().minusSeconds(3));
        assertThat(handler.handle(task)).containsEntry("status", "SKIPPED");
        assertThat(handler.synthesize(9L)).containsEntry("status", "SKIPPED");
        verifyNoInteractions(voices, store, delivery, listener);
        verify(queue, never()).markFailed(any(), any());
    }

    @Test
    void endedSessionAndStaleItemsAreNotSynthesised() {
        session.setStatus("ENDED");
        assertThat(handler.handle(task)).containsEntry("status", "DISCARDED");
        verify(queue).discard(9L);
        verify(listener).speechFinished(2L, LiveSpeechItem.SCRIPT, false, null);

        session.setStatus("LIVE");
        item.setCreatedAt(Instant.now().minus(LiveSpeechSynthesizeHandler.MAX_AGE).minusSeconds(1));
        when(queue.markFailed(eq(9L), any())).thenReturn(true);
        assertThat(handler.handle(task)).containsEntry("status", "FAILED");
        verifyNoInteractions(voices);
    }

    @Test
    void clipIsDeletedWhenTheItemWasDiscardedDuringSynthesis() {
        when(queue.markReady(eq(9L), any(), anyLong(), any(), anyLong(), any())).thenReturn(false);
        assertThat(handler.handle(task)).containsEntry("status", "DISCARDED");
        verify(store).delete("t1/live-speech/2/9.wav");
        verifyNoInteractions(delivery);
        verify(listener).speechFinished(2L, LiveSpeechItem.SCRIPT, false, null);
    }
}
