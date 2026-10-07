package com.wuyao.growth.live.player;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.live.LiveDtos;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.reply.LiveComment;
import com.wuyao.growth.live.reply.LiveCommentRepository;
import com.wuyao.growth.live.reply.LiveReplyGenerateHandler;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.voice.VoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MockCommentServiceTest {
    private final LiveSessionRepository sessions = mock(LiveSessionRepository.class);
    private final StoreAccessService stores = mock(StoreAccessService.class);
    private final LiveCommentRepository comments = mock(LiveCommentRepository.class);
    private final VoiceService voices = mock(VoiceService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final MockCommentService service = new MockCommentService(new MockCommentProvider(), sessions, comments,
            stores, voices, tasks, tx);
    private LiveSession session;

    @BeforeEach
    void setup() {
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(null));
        session = new LiveSession(); session.setId(2L); session.setStoreId(4L); session.setTenantId(9L);
        when(sessions.findForUpdate(2L)).thenReturn(Optional.of(session));
        when(comments.save(any())).thenAnswer(call -> call.getArgument(0));
        when(comments.saveAndFlush(any())).thenAnswer(call -> {
            LiveComment saved = call.getArgument(0);
            saved.setId(77L);
            return saved;
        });
    }

    private LiveComment saved() {
        ArgumentCaptor<LiveComment> captor = ArgumentCaptor.forClass(LiveComment.class);
        verify(comments, atLeast(0)).save(captor.capture());
        verify(comments, atLeast(0)).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void aCommentIsOnlyRecordedAndHandedToAWorkerWhereItIsDecidedWhetherAndHowToAnswer() {
        var result = service.receive(2L, new LiveSpeechDtos.CommentRequest("comment-2", " 可以停车吗 ", null, "voice"), 3L);
        assertThat(result.status()).isEqualTo("answering");
        assertThat(result.answer()).isNull();
        assertThat(saved()).returns("ANSWERING", LiveComment::getStatus).returns(true, LiveComment::isModelUsed)
                .returns("可以停车吗", LiveComment::getText).returns(9L, LiveComment::getTenantId)
                .returns("builtin:voice", LiveComment::getVoice).returns(false, LiveComment::isKnowledgeGap);
        // An unusable voice is rejected before any model call is paid for.
        verify(voices).resolveVoice(4L, null, "voice", 3L);
        // On the replies' own queue, never the one narration is written and synthesised on.
        verify(tasks).submit(LiveReplyGenerateHandler.TYPE, "LIVE_REPLY", Map.of("commentId", 77L), "live-reply:77", 3L);
    }

    @Test
    void theSameCommentDeliveredTwiceIsHandledOnceAndReportsWhereTheFirstGotTo() {
        LiveComment earlier = new LiveComment();
        earlier.setStatus("ANSWERED"); earlier.setAnswer("可以，门口有车位"); earlier.setSource("STORE");
        when(comments.findBySessionIdAndProviderAndExternalId(eq(2L), eq("MOCK"), anyString())).thenReturn(Optional.of(earlier));
        assertThat(service.receive(2L, new LiveSpeechDtos.CommentRequest("comment-2", "可以停车吗", null, "voice"), 3L))
                .isEqualTo(new LiveSpeechDtos.CommentResult("answered", "可以，门口有车位", "STORE"));
        earlier.setStatus("SKIPPED"); earlier.setAnswer(null); earlier.setSource(null);
        assertThat(service.receive(2L, new LiveSpeechDtos.CommentRequest("comment-2", "可以停车吗", null, "voice"), 3L).status())
                .isEqualTo("skipped");
        verify(comments, never()).save(any());
        verify(comments, never()).saveAndFlush(any());
        verifyNoInteractions(tasks, voices);
    }

    @Test
    void modelCallsPerSessionAreCapped() {
        when(comments.countBySessionIdAndModelUsedTrueAndCreatedAtAfter(eq(2L), any()))
                .thenReturn((long) MockCommentService.MAX_AI_PER_TEN_MINUTES);
        assertThat(service.receive(2L, new LiveSpeechDtos.CommentRequest("comment-3", "可以停车吗", null, "voice"), 3L).status())
                .isEqualTo("unanswered");
        assertThat(saved()).returns("UNANSWERED", LiveComment::getStatus).returns(false, LiveComment::isModelUsed)
                .returns(false, LiveComment::isKnowledgeGap);
        assertThat(saved().getNote()).contains("过于频繁");
        verifyNoInteractions(tasks);
    }

    @Test
    void aSessionSetUpForItHasItsCoHostAnswerWhateverVoiceTheConsoleAskedFor() {
        session.setConfig(new LiveDtos.Config(null, null, null, null, null, null,
                List.of(new LiveDtos.VoiceRole("builtin:host", "host"), new LiveDtos.VoiceRole("sample:8", "cohost")), true, null, true));
        service.receive(2L, new LiveSpeechDtos.CommentRequest("comment-1", "可以停车吗", null, "host"), 3L);
        assertThat(saved().getVoice()).isEqualTo("sample:8");
        verify(voices).resolveVoice(4L, 8L, null, 3L);
    }

    @Test
    void anEndedSessionTakesNoMoreComments() {
        session.setStatus("ENDED");
        assertThatThrownBy(() -> service.receive(2L, new LiveSpeechDtos.CommentRequest("comment-1", "在吗", null, "voice"), 3L))
                .isInstanceOf(BizException.class).hasMessageContaining("已结束");
        verifyNoInteractions(comments, tasks);
    }
}
