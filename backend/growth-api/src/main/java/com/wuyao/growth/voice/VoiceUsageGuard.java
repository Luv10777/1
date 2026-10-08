package com.wuyao.growth.voice;

import java.util.Optional;

/** Lets a module that speaks with a voice refuse to lose it while it still depends on it. */
public interface VoiceUsageGuard {
    /**
     * @return why this store cannot lose the sample right now (by deletion or by being closed to
     *         the store), in words for the user; empty when it can
     */
    Optional<String> inUse(Long storeId, Long sampleId);
}
