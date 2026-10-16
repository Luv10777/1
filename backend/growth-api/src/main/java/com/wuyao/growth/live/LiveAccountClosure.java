package com.wuyao.growth.live;

import com.wuyao.growth.iam.service.AccountClosureGuard;
import com.wuyao.growth.live.player.LivePlayerHub;
import com.wuyao.growth.live.player.LivePlayerPairingRepository;
import com.wuyao.growth.live.speech.LiveSpeechQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 有直播正在进行时不能关闭商户：关了会把正在播的那一场从中间掐断。
 * 商户关闭时，桌面端和安卓端的播放配对全部作废，已经连着的播放端也断开。
 */
@Component
@RequiredArgsConstructor
public class LiveAccountClosure implements AccountClosureGuard {
    private final LiveSessionRepository sessions;
    private final LivePlayerPairingRepository pairings;
    private final LiveSpeechQueue queue;
    private final LivePlayerHub hub;

    @Override
    public Optional<String> blocksClosing(Closure closure) {
        if (!closure.wholeTenant()) return Optional.empty();
        return sessions.findByTenantIdAndStatusIn(closure.tenantId(), LiveSessionService.ACTIVE).stream()
                .map(session -> "直播「" + session.getName() + "」正在进行，结束后才能注销")
                .findFirst();
    }

    @Override
    public void closed(Closure closure) {
        if (!closure.wholeTenant()) return;
        Instant now = Instant.now();
        List<Long> paired = pairings.sessionsWithOpenPairing(closure.tenantId());
        pairings.revokeForTenant(closure.tenantId(), now);
        for (Long sessionId : paired) {
            sessions.updatePlayerState(sessionId, false, now);
            queue.discardUnplayed(sessionId);
        }
        // 连接在内存里：等注销真的提交了再断开，回滚时播放端照常工作。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                paired.forEach(sessionId -> hub.revoke(closure.tenantId(), sessionId));
            }
        });
    }
}
