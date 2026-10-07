package com.wuyao.growth.live.script;

import com.wuyao.growth.common.task.TaskService;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionProduct;
import com.wuyao.growth.live.LiveSessionProductRepository;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.speech.*;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.voice.VoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.wuyao.growth.live.speech.LiveSpeechItem.*;

/**
 * Automatic product narration. It keeps a small buffer of synthesised segments ahead of the player
 * and writes the next one only when the buffer runs low, so generation is paced by what is actually
 * played. Nothing here polls: every top-up is triggered by an event (start, a clip finishing
 * playback, a segment leaving synthesis, the session resuming) and runs as a task.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveScriptService implements LiveSpeechOutcomeListener {
    /** Low-water mark: fewer READY segments than this triggers the next one. */
    static final int TARGET_BUFFER = 3;
    /** Consecutive failures after which narration stops rather than keep calling paid services. */
    static final int MAX_FAILURES = 3;
    /** Ceiling on spend if acknowledgements arrive faster than speech can really be played. */
    static final int MAX_PER_TEN_MINUTES = 60;
    /** A segment not finished within this window is written off so the session cannot stall on it. */
    static final Duration IN_FLIGHT_TIMEOUT = Duration.ofMinutes(3);
    /** Past this, a wait is worth explaining to the user. A healthy segment takes a few seconds per stage. */
    static final Duration SLOW_AFTER = Duration.ofSeconds(20);
    /** Older leftovers are written off by the handlers and say nothing about the worker now. */
    private static final Duration HINT_WINDOW = Duration.ofMinutes(10);

    private final LiveSessionRepository sessions;
    private final LiveSessionProductRepository sessionProducts;
    private final LiveScriptStateRepository states;
    private final LiveSpeechItemRepository items;
    private final LiveSpeechQueue queue;
    private final StoreAccessService stores;
    private final VoiceService voices;
    private final TaskService tasks;
    private final TransactionTemplate transactions;

    public LiveScriptDtos.Status start(Long sessionId, Long userId) {
        return transactions.execute(tx -> {
            LiveSession session = require(sessionId, userId, true);
            if (!"LIVE".equals(session.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT, "PAUSED".equals(session.getStatus())
                        ? "本场已暂停，请先继续本场" : "请先开始本场，再开启自动讲解");
            }
            if (productIds(sessionId).isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "本场还没有商品，无法自动讲解");
            }
            List<String> planned = ScriptPlan.voices(session.getConfig());
            if (planned.isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST, "请先在声音区选择本场主讲音色");
            // Reject an unusable voice or an unconfigured speech provider before any text is written.
            for (String id : planned) {
                LiveVoice voice = LiveVoice.parse(id);
                voices.resolveVoice(session.getStoreId(), voice.sampleId(), voice.builtInVoice(), userId);
            }
            LiveScriptState state = states.findById(sessionId).orElseGet(() -> {
                LiveScriptState created = new LiveScriptState();
                created.setSessionId(sessionId);
                created.setTenantId(session.getTenantId());
                return created;
            });
            state.setEnabled(true);
            state.setEnabledBy(userId);
            state.setFailures(0);
            state.setLastError(null);
            state.setUpdatedAt(Instant.now());
            state = states.save(state);
            replenish(session, state);
            return status(session, state);
        });
    }

    /** Stops writing new segments. Clips already sent to the player still play out. */
    public LiveScriptDtos.Status stop(Long sessionId, Long userId) {
        return transactions.execute(tx -> {
            LiveSession session = require(sessionId, userId, true);
            LiveScriptState state = states.findById(sessionId).orElse(null);
            if (state != null && state.isEnabled()) {
                state.setEnabled(false);
                state.setUpdatedAt(Instant.now());
            }
            return status(session, state);
        });
    }

    public LiveScriptDtos.Status status(Long sessionId, Long userId) {
        return transactions.execute(tx -> {
            LiveSession session = require(sessionId, userId, false);
            return status(session, states.findById(sessionId).orElse(null));
        });
    }

    /**
     * Whether automatic narration is running for this session right now, so that speech arriving
     * from elsewhere (a reply to a viewer) lands in the middle of it. Call inside a transaction.
     */
    public boolean narrating(LiveSession session) {
        return "LIVE".equals(session.getStatus())
                && states.findById(session.getId()).map(LiveScriptState::isEnabled).orElse(false);
    }

    public List<LiveScriptDtos.ItemView> recentItems(Long sessionId, Long userId) {
        return transactions.execute(tx -> {
            require(sessionId, userId, false);
            return items.findTop30BySessionIdOrderByIdDesc(sessionId).stream().map(LiveScriptDtos.ItemView::of).toList();
        });
    }

    /**
     * Tops the buffer up if narration is on. For callers with no user waiting on the outcome
     * (playback acknowledgements, task handlers): a failure here is logged, never propagated.
     */
    public void replenishQuietly(Long sessionId) {
        quietly(sessionId, (session, state) -> replenish(session, state));
    }

    /** The session is over: stop narrating and drop whatever was still queued. */
    public void sessionEnded(Long sessionId) {
        transactions.executeWithoutResult(tx -> sessions.findForUpdate(sessionId).ifPresent(session -> {
            states.findById(sessionId).ifPresent(state -> {
                state.setEnabled(false);
                state.setUpdatedAt(Instant.now());
            });
            queue.discardUnplayed(sessionId);
        }));
    }

    /** A segment could not be written. A fatal cause (nothing configured to write with) stops narration at once. */
    public void generationFailed(Long sessionId, String message, boolean fatal) {
        quietly(sessionId, (session, state) -> {
            if (fatal) disable(state, message);
            else recordFailure(state, message);
            replenish(session, state);
        });
    }

    @Override
    public void speechFinished(Long sessionId, String kind, boolean ready, String error) {
        if (!SCRIPT.equals(kind)) return;
        quietly(sessionId, (session, state) -> {
            if (ready) {
                state.setFailures(0);
                state.setLastError(null);
                state.setUpdatedAt(Instant.now());
            } else if (error != null) {
                recordFailure(state, error);
            }
            replenish(session, state);
        });
    }

    /** Runs under the session row lock; skipped when narration was never switched on for the session. */
    private void quietly(Long sessionId, java.util.function.BiConsumer<LiveSession, LiveScriptState> action) {
        try {
            transactions.executeWithoutResult(tx -> sessions.findForUpdate(sessionId).ifPresent(session ->
                    states.findById(sessionId).ifPresent(state -> action.accept(session, state))));
        } catch (RuntimeException e) {
            log.warn("自动讲解状态更新失败: sessionId={}", sessionId, e);
        }
    }

    /** Caller holds the session row lock, so at most one segment is ever allocated at a time. */
    private void replenish(LiveSession session, LiveScriptState state) {
        if (!state.isEnabled() || !"LIVE".equals(session.getStatus())) return;
        Long sessionId = session.getId();
        Instant now = Instant.now();
        Instant inFlightSince = now.minus(IN_FLIGHT_TIMEOUT);
        if (items.failStale(sessionId, SCRIPT, inFlightSince, "后台处理超时，已跳过", now) > 0) {
            recordFailure(state, "后台任务超时未完成，请确认 worker 进程正在运行");
            if (!state.isEnabled()) return;
        }
        if (items.countBySessionIdAndKindAndStatusInAndCreatedAtAfter(
                sessionId, SCRIPT, List.of(GENERATING, PENDING), inFlightSince) > 0) return;
        if (items.countBySessionIdAndKindAndStatusInAndCreatedAtAfter(
                sessionId, SCRIPT, List.of(READY), now.minus(LiveSpeechQueue.RETENTION)) >= TARGET_BUFFER) return;
        if (items.countBySessionIdAndKindAndCreatedAtAfter(sessionId, SCRIPT, now.minus(Duration.ofMinutes(10)))
                >= MAX_PER_TEN_MINUTES) {
            state.setLastError("自动讲解生成过于频繁，已暂缓，播放追上后会继续");
            state.setUpdatedAt(now);
            return;
        }
        List<Long> productIds = productIds(sessionId);
        if (productIds.isEmpty() || ScriptPlan.voices(session.getConfig()).isEmpty()) {
            disable(state, "本场缺少商品或主讲音色，自动讲解已停止");
            return;
        }
        ScriptPlan.Slot slot = ScriptPlan.slot(session.getConfig(), productIds, state.getNextSeq());
        state.setNextSeq(slot.seq() + 1);
        state.setUpdatedAt(now);
        String commandId = "script-" + slot.seq();
        LiveSpeechQueue.Created created = queue.create(session.getTenantId(), sessionId, new LiveSpeechQueue.Draft(
                commandId, SCRIPT, "APPEND", GENERATING, "", slot.voice(), slot.seq(), slot.productId(),
                slot.beat().code(), sha256(commandId), state.getEnabledBy()));
        if (!created.created()) return;
        Long itemId = created.item().getId();
        tasks.submit(LiveScriptGenerateHandler.TYPE, LiveSpeechSynthesizeHandler.QUEUE, Map.of("itemId", itemId),
                "live-script:" + itemId, state.getEnabledBy());
    }

    private void recordFailure(LiveScriptState state, String message) {
        state.setFailures(state.getFailures() + 1);
        state.setLastError(clip(message));
        state.setUpdatedAt(Instant.now());
        if (state.getFailures() >= MAX_FAILURES) {
            disable(state, "连续 " + MAX_FAILURES + " 次失败，自动讲解已停止：" + message);
        }
    }

    private void disable(LiveScriptState state, String message) {
        state.setEnabled(false);
        state.setLastError(clip(message));
        state.setUpdatedAt(Instant.now());
    }

    private LiveScriptDtos.Status status(LiveSession session, LiveScriptState state) {
        Long sessionId = session.getId();
        Instant now = Instant.now();
        long inFlight = items.countBySessionIdAndKindAndStatusInAndCreatedAtAfter(
                sessionId, SCRIPT, List.of(GENERATING, PENDING), now.minus(IN_FLIGHT_TIMEOUT));
        long buffered = items.countBySessionIdAndKindAndStatusInAndCreatedAtAfter(
                sessionId, SCRIPT, List.of(READY), now.minus(LiveSpeechQueue.RETENTION));
        // Any kind counts here: manual speech waits on the same worker.
        String hint = items.findFirstBySessionIdAndStatusInAndCreatedAtAfterOrderByIdAsc(
                        sessionId, List.of(GENERATING, PENDING), now.minus(HINT_WINDOW))
                .map(item -> slowness(item, now)).orElse(null);
        return new LiveScriptDtos.Status(state != null && state.isEnabled(), session.getStatus(), TARGET_BUFFER,
                buffered, inFlight, state == null ? 0 : state.getNextSeq(), state == null ? 0 : state.getFailures(),
                state == null ? null : state.getLastError(), hint);
    }

    /**
     * Says which of two different problems is behind a long wait: nobody has picked the item up
     * (no worker, or the worker is busy elsewhere), or a worker has it and a provider is slow.
     */
    static String slowness(LiveSpeechItem item, Instant now) {
        if (item.getStartedAt() == null) {
            Instant queued = item.getQueuedAt() == null ? item.getCreatedAt() : item.getQueuedAt();
            long waited = Duration.between(queued, now).toSeconds();
            return waited < SLOW_AFTER.toSeconds() ? null
                    : "有播报已排队 " + waited + " 秒，还没有 worker 开始处理。请确认后台 worker 进程已启动，且处理队列包含 LIVE。";
        }
        long running = Duration.between(item.getStartedAt(), now).toSeconds();
        if (running < SLOW_AFTER.toSeconds()) return null;
        return GENERATING.equals(item.getStatus())
                ? "文案模型已处理 " + running + " 秒还没有返回。worker 在正常工作；一直这么慢通常是模型开启了思考模式，或模型服务繁忙。"
                : "语音合成已处理 " + running + " 秒还没有返回。worker 在正常工作，可能是语音服务繁忙。";
    }

    private List<Long> productIds(Long sessionId) {
        return sessionProducts.findBySessionIdOrderBySortOrderAscIdAsc(sessionId).stream()
                .map(LiveSessionProduct::getProductId).toList();
    }

    private LiveSession require(Long sessionId, Long userId, boolean lock) {
        LiveSession session = (lock ? sessions.findForUpdate(sessionId) : sessions.findById(sessionId))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "直播场次不存在"));
        stores.requireAccess(session.getStoreId(), userId);
        return session;
    }

    private static String clip(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
