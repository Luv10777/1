package com.wuyao.growth.live;

import com.wuyao.growth.store.StoreClosureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** A store with a session on air stays open: closing it would cut the stream off mid-sentence. */
@Component
@RequiredArgsConstructor
public class LiveStoreClosureGuard implements StoreClosureGuard {
    private final LiveSessionRepository sessions;

    @Override
    public Optional<String> blocksClosing(Long storeId) {
        return sessions.findByStoreIdAndStatusIn(storeId, LiveSessionService.ACTIVE).stream()
                .map(session -> "这家店有正在进行的直播「" + session.getName() + "」，结束这一场后才能关店")
                .findFirst();
    }
}
