package com.wuyao.growth.live.reply;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.live.LiveDtos;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.store.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The console's view of a session's comments and replies, newest first. */
@Service
@RequiredArgsConstructor
public class LiveCommentFeed {
    private final LiveSessionRepository sessions;
    private final LiveCommentRepository comments;
    private final StoreAccessService stores;
    private final LiveReplyKnowledge knowledge;
    static final int MAX_UNANSWERED = 50;

    @Transactional(readOnly = true)
    public LiveDtos.RealtimeView realtime(Long sessionId, Long userId) {
        LiveSession session = sessions.findById(sessionId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "直播场次不存在"));
        stores.requireAccess(session.getStoreId(), userId);
        // No platform is connected: whatever is listed came from the console's simulated comments.
        return new LiveDtos.RealtimeView("NOT_CONNECTED", "真实抖音弹幕尚未接入",
                comments.findTop50BySessionIdOrderByIdDesc(sessionId).stream()
                        .map(comment -> new LiveDtos.RealtimeItem(String.valueOf(comment.getId()), "COMMENT",
                                comment.getText(), comment.getAnswer(), comment.getSource(), comment.getStatus(),
                                comment.getNote(), comment.getProvider(), comment.getCreatedAt())).toList());
    }

    /**
     * Questions asked during the session that went unanswered for want of material, most asked
     * first. One that the libraries can answer by now is left out, so the list shrinks as the
     * merchant fills the gaps.
     */
    @Transactional(readOnly = true)
    public List<LiveDtos.UnansweredQuestion> unanswered(Long sessionId, Long userId) {
        LiveSession session = sessions.findById(sessionId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "直播场次不存在"));
        stores.requireAccess(session.getStoreId(), userId);
        Set<String> answerable = new HashSet<>();
        knowledge.library(session, userId).forEach(saved -> answerable.add(LiveReplyKnowledge.normalize(saved.question())));
        Map<String, LiveDtos.UnansweredQuestion> questions = new LinkedHashMap<>();
        // Newest first, so the wording kept for a repeated question is the latest one.
        for (LiveComment comment : comments.findTop500BySessionIdAndKnowledgeGapTrueOrderByIdDesc(sessionId)) {
            String key = LiveReplyKnowledge.normalize(comment.getText());
            if (key.isEmpty() || answerable.contains(key)) continue;
            questions.merge(key, new LiveDtos.UnansweredQuestion(comment.getText(), 1, comment.getCreatedAt()),
                    (kept, again) -> new LiveDtos.UnansweredQuestion(kept.text(), kept.count() + 1, kept.lastAskedAt()));
        }
        return questions.values().stream()
                .sorted(Comparator.comparingInt(LiveDtos.UnansweredQuestion::count).reversed())
                .limit(MAX_UNANSWERED).toList();
    }
}
