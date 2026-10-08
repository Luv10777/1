package com.wuyao.growth.live;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface LiveSessionRepository extends JpaRepository<LiveSession, Long> {

    List<LiveSession> findByStoreIdOrderByUpdatedAtDescIdDesc(Long storeId);

    List<LiveSession> findByStoreIdAndStatusIn(Long storeId, Collection<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LiveSession s where s.id = :id")
    Optional<LiveSession> findForUpdate(@Param("id") Long id);

    /** Runtime telemetry must not invalidate the optimistic version of saved draft configuration. */
    @Modifying
    @Query(value = "update live_sessions set player_paired = :paired, " +
            "player_last_heartbeat_at = case when :paired then :at else player_last_heartbeat_at end " +
            "where id = :id and status in ('DRAFT','LIVE','PAUSED')", nativeQuery = true)
    int updatePlayerState(@Param("id") Long id, @Param("paired") boolean paired, @Param("at") Instant at);

    @Modifying
    @Query(value = "update live_sessions set player_paired = true, player_last_heartbeat_at = :at " +
            "where id = :id and status in ('DRAFT','LIVE','PAUSED') and exists " +
            "(select 1 from live_player_pairings p where p.id = :pairingId and p.session_id = :id " +
            "and p.revoked_at is null and p.expires_at > :at)", nativeQuery = true)
    int recordPlayerHeartbeat(@Param("id") Long id, @Param("pairingId") Long pairingId, @Param("at") Instant at);
}
