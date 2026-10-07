package com.wuyao.growth.live;

import com.wuyao.growth.live.speech.LiveVoice;
import com.wuyao.growth.voice.VoiceUsageGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A session on air cannot change its voices, so deleting one of them would silence it for good.
 * Drafts are not protected: they lose the voice and are stopped from starting without a host.
 */
@Component
@RequiredArgsConstructor
public class LiveVoiceUsageGuard implements VoiceUsageGuard {
    private final LiveSessionRepository sessions;

    @Override
    public Optional<String> inUse(Long storeId, Long sampleId) {
        String voice = new LiveVoice(sampleId, null).encode();
        return sessions.findByStoreIdAndStatusIn(storeId, LiveSessionService.ACTIVE).stream()
                .filter(session -> LiveVoice.lineup(session.getConfig()).contains(voice))
                .map(session -> "这个音色正在场次「" + session.getName() + "」中使用，结束这一场后才能删除")
                .findFirst();
    }
}
