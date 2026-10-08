package com.wuyao.growth.live.player;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface LivePlayerPairingRepository extends JpaRepository<LivePlayerPairing, Long> {
    Optional<LivePlayerPairing> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update LivePlayerPairing p set p.revokedAt = :now where p.sessionId = :sessionId and p.revokedAt is null")
    int revokeForSession(@Param("sessionId") Long sessionId, @Param("now") Instant now);
}
