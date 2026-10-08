package com.wuyao.growth.live.reply;

import com.wuyao.growth.brand.BrandService;
import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.gateway.ProviderRequest;
import com.wuyao.growth.common.gateway.ProviderResult;
import com.wuyao.growth.common.task.Task;
import com.wuyao.growth.common.task.TaskHandler;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.live.LiveDtos;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.player.LiveSpeechDtos;
import com.wuyao.growth.live.player.LiveSpeechService;
import com.wuyao.growth.live.script.LiveScriptService;
import com.wuyao.growth.live.script.ScriptGuard;
import com.wuyao.growth.live.script.ScriptPrompt;
import com.wuyao.growth.live.speech.LiveSpeechItem;
import com.wuyao.growth.live.speech.LiveSpeechItemRepository;
import com.wuyao.growth.live.speech.LiveSpeechSynthesizeHandler;
import com.wuyao.growth.live.speech.LiveVoice;
import com.wuyao.growth.store.StoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what becomes of one viewer comment and, if it deserves an answer, queues the answer as
 * speech.
 *
 * A comment that is one of the saved questions word for word needs no judgement: the merchant's
 * answer is put into spoken words and, should that not work out, spoken as it stands. Anything
 * else goes to the text model with the session's saved material, and the model says what kind of
 * comment it is, which saved Q&A asks the same thing, and what to say. A question is answered and a
 * wish to buy is met with a nudge; a complaint or anything else meant for a person is flagged and
 * left unanswered; chatter is let go, and so is any question about whether the host is a person or
 * a machine, which no reply ever speaks of. That call is asked to
 * reason first: a wrong match is spoken aloud to a room, and a few seconds are worth avoiding it.
 *
 * A failure settles the comment instead of asking the task framework to retry: a late answer to a
 * live question is worth nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveReplyGenerateHandler implements TaskHandler {
    public static final String TYPE = "LIVE_REPLY_GENERATE";
    /** One more attempt is allowed when the first output fails the checks. */
    static final int MAX_DRAFTS = 2;
    /** Also bounds a re-run after a crashed worker's lease expires. */
    static final Duration MAX_AGE = Duration.ofMinutes(3);
    /** Speech is refused beyond this length, whoever worded it. */
    private static final int MAX_SPOKEN = 1000;
    private static final Set<String> ACTIVE = Set.of("DRAFT", "LIVE", "PAUSED");

    private final CommentScreen screen;
    private final LiveCommentRepository comments;
    private final LiveSessionRepository sessions;
    private final LiveSpeechItemRepository items;
    private final LiveReplyKnowledge knowledge;
    private final LiveSpeechService speech;
    private final LiveSpeechSynthesizeHandler synthesizer;
    private final LiveScriptService scripts;
    private final BrandService brands;
    private final StoreService stores;
    private final AiGateway gateway;
    private final TransactionTemplate transactions;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> handle(Task task) {
        Long commentId = ((Number) task.getPayload().get("commentId")).longValue();
        Job job = transactions.execute(tx -> prepare(commentId));
        if (job == null) return result(commentId, "SKIPPED", null);

        Outcome outcome;
        try {
            outcome = job.exact() != null ? polish(job, task.getId()) : judge(job, task.getId());
            if (outcome.spoken() != null) {
                LiveVoice voice = LiveVoice.parse(job.voice());
                speech.submit(job.sessionId(), LiveSpeechItem.REPLY, new LiveSpeechDtos.SpeechRequest(job.commandId(),
                        "INTERRUPT", outcome.spoken(), voice == null ? null : voice.sampleId(),
                        voice == null ? null : voice.builtInVoice()),
                        ReplySpeech.bridge(job.commandId(), job.narrating(), job.byCohost()), job.userId());
                outcome = spoken(job, outcome);
            }
        } catch (BizException e) {
            return settle(commentId, new Outcome(LiveComment.FAILED, null, null, e.getMessage(), false));
        } catch (RuntimeException e) {
            log.warn("直播弹幕回复生成异常: commentId={}", commentId, e);
            return settle(commentId, new Outcome(LiveComment.FAILED, null, null, "回复生成未完成，请稍后重试", false));
        }
        return settle(commentId, outcome);
    }

    /**
     * Synthesises the reply here and now rather than leaving it to the task just queued for it:
     * with several comments waiting, that task would come after every one of them had been judged.
     * A clip that could not be made is reported on the comment, where the operator is looking.
     */
    private Outcome spoken(Job job, Outcome outcome) {
        Long itemId = transactions.execute(tx -> items.findBySessionIdAndCommandId(job.sessionId(), job.commandId())
                .map(LiveSpeechItem::getId).orElse(null));
        if (itemId == null) return outcome;
        Map<String, Object> clip = synthesizer.synthesize(itemId);
        return LiveSpeechItem.FAILED.equals(clip.get("status"))
                ? new Outcome(LiveComment.FAILED, null, outcome.source(), "回复已写好，但语音没有合成出来：" + clip.get("error"), false)
                : outcome;
    }

    private Job prepare(Long commentId) {
        LiveComment comment = comments.findForUpdate(commentId).orElse(null);
        if (comment == null || !LiveComment.ANSWERING.equals(comment.getStatus())) return null;
        LiveSession session = sessions.findById(comment.getSessionId()).orElse(null);
        if (session == null || !ACTIVE.contains(session.getStatus())) {
            comment.settle(LiveComment.UNANSWERED, null, null, "场次已结束");
            return null;
        }
        // A re-run after the reply was already queued: record it, never ask the model again.
        Optional<LiveSpeechItem> spoken = items.findBySessionIdAndCommandId(session.getId(), comment.commandId());
        if (spoken.isPresent()) {
            comment.settle(LiveComment.ANSWERED, spoken.get().getText(), LiveComment.SOURCE_AI, null);
            return null;
        }
        if (comment.getCreatedAt().isBefore(Instant.now().minus(MAX_AGE))) {
            comment.settle(LiveComment.UNANSWERED, null, null, "等待回答超时，已跳过");
            return null;
        }
        Long userId = comment.getCreatedBy();
        List<LiveReplyKnowledge.Candidate> saved = knowledge.candidates(session, userId);
        LiveDtos.Config config = session.getConfig();
        return new Job(comment.getTenantId(), session.getId(), comment.commandId(), comment.getText(),
                // The exact match is looked for among everything saved, not only what fits in a prompt.
                LiveReplyKnowledge.exact(saved, comment.getText()), ReplyPrompt.shown(saved),
                knowledge.productFacts(session, userId),
                ScriptPrompt.brand(brands.profileForStore(session.getStoreId()).orElse(null)),
                // Like the products and the brand, read as it is now; dated arrangements are judged by the shop's own calendar.
                ReplyPrompt.store(stores.profile(session.getStoreId()).orElse(null), LocalDate.now(ZoneId.of("Asia/Shanghai"))),
                comment.getVoice(), userId,
                config == null ? null : config.persona(), scripts.narrating(session),
                // The voice was fixed when the comment arrived; this only asks whether it was the co-host's.
                LiveVoice.answerer(config).filter(voice -> voice.equals(comment.getVoice())).isPresent());
    }

    /**
     * The comment is a saved question: put the merchant's answer into spoken words. Whatever goes
     * wrong here, the answer itself is known and safe, so it is spoken as written instead.
     */
    private Outcome polish(Job job, Long taskId) {
        LiveReplyKnowledge.Candidate saved = job.exact();
        String asWritten = job.narrating() ? ReplySpeech.withLeadIn(saved.question(), saved.answer(), job.commandId()) : saved.answer();
        if (asWritten.length() > MAX_SPOKEN) asWritten = saved.answer();
        Outcome fallback = asWritten.length() > MAX_SPOKEN
                ? new Outcome(LiveComment.UNANSWERED, null, saved.source(), "命中的问答回复超过 1000 字，请先缩短知识库回复", false)
                : new Outcome(LiveComment.ANSWERED, asWritten, saved.source(), null, false);
        try {
            String system = ReplyPrompt.polishSystem(job.persona(), job.narrating(), job.byCohost(), ReplySpeech.opening(job.commandId()));
            String material = ReplyPrompt.polishUser(saved.question(), saved.answer(), null);
            // Spoken words run longer than a written note, and the lead-in repeats the question.
            int maxChars = Math.min(MAX_SPOKEN, saved.answer().length() * 2 + saved.question().length() + 60);
            String rejection = null;
            for (int draft = 1; draft <= MAX_DRAFTS; draft++) {
                String raw = write(job, ReplyPrompt.polishUser(saved.question(), saved.answer(), rejection), system,
                        false, "live-reply-" + taskId + "-p" + draft);
                if (raw == null) return fallback;
                String text = ScriptGuard.clean(raw);
                Optional<String> violation = violation(text, material, maxChars);
                if (violation.isEmpty()) return new Outcome(LiveComment.ANSWERED, text, saved.source(), null, false);
                rejection = violation.get();
            }
        } catch (RuntimeException e) {
            log.info("直播弹幕回复润色未完成，改为原样播出: {}", e.getMessage());
        }
        return fallback;
    }

    /** Anything else: the model decides whether to answer, on what, and in which words. */
    private Outcome judge(Job job, Long taskId) {
        if (job.candidates().isEmpty() && job.facts().isBlank()) {
            return new Outcome(LiveComment.UNANSWERED, null, null, "本场没有可用于回答的资料", false);
        }
        String system = ReplyPrompt.system(job.persona(), job.narrating(), job.byCohost(), !job.brand().isEmpty(), !job.store().isEmpty(),
                ReplySpeech.opening(job.commandId()));
        String facts = ReplyPrompt.material(job.facts(), job.store(), job.brand());
        // What the merchant saved, and therefore what the model's wording is checked against.
        // The viewer's text is left out: a number a viewer typed is not a fact.
        String material = ReplyPrompt.user("", job.candidates(), facts, null);
        String rejection = null;
        boolean understood = false;
        boolean gap = false;
        for (int draft = 1; draft <= MAX_DRAFTS; draft++) {
            String raw = write(job, ReplyPrompt.user(job.question(), job.candidates(), facts, rejection), system,
                    true, "live-reply-" + taskId + "-" + draft);
            if (raw == null) {
                throw BizException.of(com.wuyao.growth.common.web.ErrorCode.INTERNAL_ERROR,
                        "尚未配置文本大模型，无法生成 AI 回答。请在服务端配置 TEXT_WRITER 模型后重试");
            }
            ReplyPrompt.Decision decision = ReplyPrompt.parse(raw);
            if (decision == null) {
                understood = false;
                rejection = "输出不是要求的 JSON 对象";
                continue;
            }
            understood = true;
            switch (decision.kind()) {
                case ReplyPrompt.OTHER -> {
                    return new Outcome(LiveComment.SKIPPED, null, null, "无需回复", false);
                }
                case ReplyPrompt.HANDOFF -> {
                    // Whatever the model wrote is not spoken: these are for a person, and must not be missed.
                    return new Outcome(LiveComment.ATTENTION, null, null,
                            decision.summary().isEmpty() ? "需要人工处理" : "需要人工处理：" + decision.summary(), false);
                }
                default -> { }
            }
            boolean asked = ReplyPrompt.QUESTION.equals(decision.kind());
            if (decision.answer().isEmpty()) {
                // A question nobody could answer is a gap in the material; a wish to buy left alone is not.
                return asked ? new Outcome(LiveComment.UNANSWERED, null, null, "本场资料里没有相关内容，AI 未作答", true)
                        : new Outcome(LiveComment.SKIPPED, null, null, "无需回复", false);
            }
            gap = asked;
            LiveReplyKnowledge.Candidate basis = decision.basis() >= 1 && decision.basis() <= job.candidates().size()
                    ? job.candidates().get(decision.basis() - 1) : null;
            String text = ScriptGuard.clean(decision.answer());
            // An answer resting on a long saved answer may run as long as that one does.
            int maxChars = Math.min(MAX_SPOKEN, Math.max(ReplyPrompt.MAX_CHARS, basis == null ? 0 : basis.answer().length() * 2)
                    + (job.narrating() ? ReplyPrompt.LEAD_IN_CHARS : 0));
            Optional<String> violation = violation(text, material, maxChars);
            if (violation.isEmpty()) {
                return new Outcome(LiveComment.ANSWERED, text, basis == null ? LiveComment.SOURCE_AI : basis.source(), null, false);
            }
            // Talk of who or what the host is has nothing to do with what the merchant's material covers.
            if (ReplyPrompt.speaksOfIdentity(text)) gap = false;
            rejection = violation.get();
        }
        // Output that cannot be read says nothing about the comment or the material.
        if (!understood) return new Outcome(LiveComment.FAILED, null, null, "AI 没有按要求的格式作答，这条没有回复", false);
        // The material did not yield words that could be spoken. For a question, that is a gap in it.
        return new Outcome(LiveComment.UNANSWERED, null, null, "AI 回答未通过校验（" + rejection + "），没有播出", gap);
    }

    /** The checks every model-worded reply passes before it is spoken. */
    private Optional<String> violation(String text, String material, int maxChars) {
        Optional<String> violation = ReplyPrompt.speaksOfIdentity(text)
                ? Optional.of("不能谈论主播是真人还是 AI，两种说法都不要出现")
                // The reply opens by saying what the viewer said: this is where their words could get on air.
                : screen.unspeakable(text) ? Optional.of("出现了不能在直播里说的词，换一种说法，不要复述观众的原话")
                : ScriptGuard.violation(text, material, ReplyPrompt.MIN_CHARS, maxChars);
        // What was refused is kept nowhere else; without it a wrong refusal cannot be told from a right one.
        violation.ifPresent(reason -> log.info("直播弹幕回复未通过校验: reason={} text={}", reason, text));
        return violation;
    }

    /** @return the model's text, or null when no text model is configured */
    private String write(Job job, String prompt, String system, boolean reasoning, String idempotencyKey) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("system", system);
        // Neither judging a comment nor rewording an answer is a creative task.
        options.put("temperature", 0.2);
        if (reasoning) options.put("reasoning", true);
        // Stable per task and draft, so a retried task does not look like a new request.
        ProviderResult result = gateway.invoke(new ProviderRequest(ModelAlias.TEXT_WRITER, job.tenantId(), prompt,
                options, idempotencyKey));
        // The placeholder adapter answers every capability but writes nothing: it has no "text".
        return result.succeeded() && result.output() != null && result.output().get("text") instanceof String text
                ? text : null;
    }

    private Map<String, Object> settle(Long commentId, Outcome outcome) {
        transactions.executeWithoutResult(tx -> comments.findForUpdate(commentId)
                .filter(comment -> LiveComment.ANSWERING.equals(comment.getStatus()))
                .ifPresent(comment -> {
                    comment.settle(outcome.status(), outcome.spoken(), outcome.source(), outcome.note());
                    comment.setKnowledgeGap(outcome.gap());
                }));
        return result(commentId, outcome.status(), outcome.note());
    }

    private static Map<String, Object> result(Long commentId, String status, String note) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commentId", commentId);
        result.put("status", status);
        if (note != null) result.put("note", note);
        return result;
    }

    /**
     * @param exact the saved Q&A whose question is this comment word for word, or null
     * @param brand the description of the store's brand as offered to the model; empty when it has none
     * @param store what the merchant recorded about the store itself, as offered to the model; empty when nothing was
     */
    private record Job(Long tenantId, Long sessionId, String commandId, String question,
                       LiveReplyKnowledge.Candidate exact, List<LiveReplyKnowledge.Candidate> candidates,
                       String facts, String brand, String store, String voice, Long userId, LiveDtos.Persona persona, boolean narrating,
                       boolean byCohost) { }

    /**
     * @param spoken what is said, or null when nothing is
     * @param gap    a real question the material could not answer
     */
    private record Outcome(String status, String spoken, String source, String note, boolean gap) { }
}
