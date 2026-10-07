package com.wuyao.growth.live.player;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.reply.LiveComment;
import com.wuyao.growth.live.reply.LiveCommentRepository;
import com.wuyao.growth.live.reply.LiveReplyGenerateHandler;
import com.wuyao.growth.live.reply.LiveReplyLane;
import com.wuyao.growth.live.speech.LiveVoice;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.voice.VoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Takes a simulated viewer comment. The request only records it and hands it to a worker: whether
 * it is answered, and in which words, is decided there, where the text model can be asked without a
 * user waiting on the call.
 */
@Service
@RequiredArgsConstructor
public class MockCommentService {
    /** Ceiling on model calls per session, whatever the comment source sends. */
    static final int MAX_AI_PER_TEN_MINUTES = 30;
    private static final Set<String> ACTIVE = Set.of("DRAFT", "LIVE", "PAUSED");

    private final MockCommentProvider provider;
    private final LiveSessionRepository sessions;
    private final LiveCommentRepository comments;
    private final StoreAccessService stores;
    private final VoiceService voices;
    private final TaskService tasks;
    private final TransactionTemplate transactions;

    public LiveSpeechDtos.CommentResult receive(Long sessionId, LiveSpeechDtos.CommentRequest request, Long userId) {
        var incoming = provider.receive(request.id(), request.text());
        String externalId = PlayerTokenService.sha256(incoming.id());
        return transactions.execute(tx -> {
            // The row lock is what makes look-up-then-insert safe against the same comment arriving twice.
            LiveSession session = sessions.findForUpdate(sessionId)
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "直播场次不存在"));
            stores.requireAccess(session.getStoreId(), userId);
            if (!ACTIVE.contains(session.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT, "该场次已结束");
            }
            Optional<LiveComment> seen = comments.findBySessionIdAndProviderAndExternalId(
                    sessionId, incoming.provider(), externalId);
            if (seen.isPresent()) return result(seen.get());

            // A session set up for it has its co-host answer, whatever voice the console asked for.
            String cohost = LiveVoice.answerer(session.getConfig()).orElse(null);
            LiveVoice voice = cohost != null ? LiveVoice.parse(cohost) : LiveVoice.of(request.sampleId(), request.builtInVoice());
            LiveComment comment = new LiveComment();
            comment.setTenantId(session.getTenantId());
            comment.setSessionId(sessionId);
            comment.setProvider(incoming.provider());
            comment.setExternalId(externalId);
            comment.setText(incoming.text());
            comment.setVoice(voice.encode());
            comment.setCreatedBy(userId);

            if (comments.countBySessionIdAndModelUsedTrueAndCreatedAtAfter(sessionId,
                    Instant.now().minus(Duration.ofMinutes(10))) >= MAX_AI_PER_TEN_MINUTES) {
                comment.settle(LiveComment.UNANSWERED, null, null, "弹幕过于频繁，这条没有处理");
                return result(comments.save(comment));
            }
            // Fail here, where the user is waiting, rather than after a model call has been paid for.
            voices.resolveVoice(session.getStoreId(), voice.sampleId(), voice.builtInVoice(), userId);
            comment.setStatus(LiveComment.ANSWERING);
            comment.setModelUsed(true);
            comment = comments.saveAndFlush(comment);
            // On its own queue, so that it is not kept waiting behind narration being written and synthesised.
            tasks.submit(LiveReplyGenerateHandler.TYPE, LiveReplyLane.QUEUE,
                    Map.of("commentId", comment.getId()), "live-reply:" + comment.getId(), userId);
            return result(comment);
        });
    }

    private static LiveSpeechDtos.CommentResult result(LiveComment comment) {
        String status = switch (comment.getStatus()) {
            case LiveComment.ANSWERING -> "answering";
            case LiveComment.ANSWERED -> "answered";
            case LiveComment.SKIPPED -> "skipped";
            case LiveComment.ATTENTION -> "attention";
            default -> "unanswered";
        };
        return new LiveSpeechDtos.CommentResult(status, comment.getAnswer(), comment.getSource());
    }
}
