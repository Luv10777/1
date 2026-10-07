package com.wuyao.growth.live.player;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.live.LiveSession;
import com.wuyao.growth.live.LiveSessionRepository;
import com.wuyao.growth.live.script.LiveScriptService;
import com.wuyao.growth.live.speech.LiveSpeechItem;
import com.wuyao.growth.live.speech.LiveSpeechQueue;
import com.wuyao.growth.store.StoreAccessService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketSession;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LivePlayerService {
    /** A paired player is "connected" while its heartbeats keep arriving, on whichever instance. */
    private static final long HEARTBEAT_FRESH_SECONDS = 30;
    private final LiveSessionRepository sessions;
    private final LivePlayerPairingRepository pairings;
    private final StoreAccessService stores;
    private final PlayerTokenService tokens;
    private final LivePlayerHub hub;
    private final LiveSpeechQueue queue;
    private final LiveScriptService scripts;
    private final TransactionTemplate transactions;

    public LivePlayerDtos.Pairing issue(Long sessionId, Long userId) {
        LivePlayerDtos.Pairing result = transactions.execute(tx -> {
            LiveSession session = requireOwner(sessionId, userId, true);
            PlayerTokenService.Issued issued = tokens.issue(session.getTenantId(), sessionId);
            pairings.revokeForSession(sessionId, Instant.now());
            sessions.updatePlayerState(sessionId, false, Instant.now());
            // The queue is kept: a new credential is what a refreshed console page asks for, and it
            // must carry on with what is still unplayed, not pay to produce it again.
            LivePlayerPairing pairing = new LivePlayerPairing();
            pairing.setTenantId(session.getTenantId());
            pairing.setSessionId(sessionId);
            pairing.setTokenHash(issued.hash());
            pairing.setExpiresAt(issued.expiresAt());
            pairing.setCreatedBy(userId);
            pairings.save(pairing);
            return new LivePlayerDtos.Pairing(issued.value(), "/player?token="
                    + URLEncoder.encode(issued.value(), StandardCharsets.UTF_8), issued.expiresAt());
        });
        hub.revoke(TenantContext.require(), sessionId);
        return result;
    }

    /** Ends playback for the session: the credential is void and whatever was still queued is dropped. */
    public void revoke(Long sessionId, Long userId) {
        transactions.executeWithoutResult(tx -> {
            requireOwner(sessionId, userId, true);
            pairings.revokeForSession(sessionId, Instant.now());
            sessions.updatePlayerState(sessionId, false, Instant.now());
            queue.discardUnplayed(sessionId);
        });
        hub.revoke(TenantContext.require(), sessionId);
        scripts.replenishQuietly(sessionId);
    }

    public LivePlayerDtos.Status status(Long sessionId, Long userId) {
        return transactions.execute(tx -> {
            LiveSession session = requireOwner(sessionId, userId, false);
            Instant heartbeat = session.getPlayerLastHeartbeatAt();
            boolean connected = session.isPlayerPaired() && heartbeat != null
                    && heartbeat.isAfter(Instant.now().minusSeconds(HEARTBEAT_FRESH_SECONDS));
            return new LivePlayerDtos.Status(session.isPlayerPaired(), connected, heartbeat,
                    (int) queue.unplayed(sessionId), hub.deviceType(session.getTenantId(), sessionId));
        });
    }

    /** The control route can only queue our generated sound-check fixture, never an arbitrary URL. */
    public boolean enqueue(Long sessionId, Long userId, LivePlayerDtos.Command command) {
        if (!PlayerTestAudio.URL.equals(command.audioUrl())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请通过语音合成接口创建播报音频");
        }
        validateCommand(command);
        boolean created = Boolean.TRUE.equals(transactions.execute(tx -> {
            LiveSession session = requireOwner(sessionId, userId, true);
            var result = queue.create(session.getTenantId(), sessionId, new LiveSpeechQueue.Draft(command.id(),
                    LiveSpeechItem.TEST, command.mode(), LiveSpeechItem.READY, command.text(), null, null, null, null,
                    PlayerTokenService.sha256(command.mode() + "\n" + command.text()), userId));
            if (result.created()) {
                result.item().setDurationMillis(PlayerTestAudio.DURATION_MILLIS);
                result.item().setPauseOffsets(PlayerTestAudio.PAUSE_OFFSETS);
            }
            return result.created();
        }));
        if (created) hub.deliver(TenantContext.require(), sessionId);
        return created;
    }

    public LivePlayerDtos.Scope authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) throw invalidToken();
        Claims claims = tokens.parse(token);
        if (claims == null) throw invalidToken();
        Long tenantId;
        Long sessionId;
        try {
            tenantId = claims.get("tid", Number.class).longValue();
            sessionId = Long.valueOf(claims.getSubject());
        } catch (RuntimeException e) { throw invalidToken(); }
        if (tenantId <= 0 || sessionId <= 0) throw invalidToken();
        return TenantContext.runAs(tenantId, () -> transactions.execute(tx -> {
            LivePlayerPairing pairing = pairings.findByTokenHash(PlayerTokenService.sha256(token))
                    .filter(p -> p.getTenantId().equals(tenantId) && p.getSessionId().equals(sessionId)
                            && p.getRevokedAt() == null && p.getExpiresAt().isAfter(Instant.now()))
                    .orElseThrow(LivePlayerService::invalidToken);
            LiveSession session = sessions.findById(sessionId).orElseThrow(LivePlayerService::invalidToken);
            requireActive(session);
            return new LivePlayerDtos.Scope(tenantId, sessionId, pairing.getId(), session.getName());
        }));
    }

    public void heartbeat(LivePlayerDtos.Scope scope) {
        TenantContext.runAs(scope.tenantId(), () -> transactions.execute(tx ->
                sessions.recordPlayerHeartbeat(scope.sessionId(), scope.pairingId(), Instant.now())));
    }

    public void disconnected(LivePlayerDtos.Scope scope) {
        TenantContext.runAs(scope.tenantId(), () -> transactions.execute(tx ->
                sessions.updatePlayerState(scope.sessionId(), false, Instant.now())));
    }

    /** A clip finished playing: free its slot, and let automatic narration top the buffer back up. */
    public void acknowledge(LivePlayerDtos.Scope scope, WebSocketSession socket, String id) {
        if (hub.acknowledge(scope, socket, id).isEmpty()) return;
        TenantContext.runAs(scope.tenantId(), () -> {
            scripts.replenishQuietly(scope.sessionId());
            return null;
        });
    }

    private LiveSession requireOwner(Long sessionId, Long userId, boolean active) {
        LiveSession session = (active ? sessions.findForUpdate(sessionId) : sessions.findById(sessionId))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "直播场次不存在"));
        stores.requireAccess(session.getStoreId(), userId);
        if (active) requireActive(session);
        return session;
    }

    private static void requireActive(LiveSession session) {
        if (!Set.of("DRAFT", "LIVE", "PAUSED").contains(session.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT, "该场次已结束，不能继续播报");
        }
    }

    private static BizException invalidToken() {
        return BizException.of(ErrorCode.FORBIDDEN, "播报链接无效或已过期，请重新配对");
    }

    private static void validateCommand(LivePlayerDtos.Command command) {
        if (command.id() == null || command.id().isBlank() || command.id().length() > 100
                || command.mode() == null || !Set.of("APPEND", "INTERRUPT", "CLEAR_REPLAY").contains(command.mode())
                || command.text() == null || command.text().isBlank() || command.text().length() > 4000) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "播报音频参数不合法");
        }
    }
}
