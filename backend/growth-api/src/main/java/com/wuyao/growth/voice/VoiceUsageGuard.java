package com.wuyao.growth.voice;

import java.util.Optional;

/** Lets a module that speaks with a voice refuse its deletion while it still depends on it. */
public interface VoiceUsageGuard {
    /** @return why the sample cannot be deleted right now, in words for the user; empty when it can */
    Optional<String> inUse(Long storeId, Long sampleId);
}
