package com.wuyao.growth.live.speech;

/** Implemented by whatever holds the player connections on this instance. */
public interface LiveSpeechDelivery {
    /** Pushes anything newly READY for the session to its connected player, if it is connected here. */
    void deliver(Long tenantId, Long sessionId);
}
