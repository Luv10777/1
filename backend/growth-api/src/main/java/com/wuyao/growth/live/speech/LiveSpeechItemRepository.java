package com.wuyao.growth.live.speech;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LiveSpeechItemRepository extends JpaRepository<LiveSpeechItem, Long> {

    Optional<LiveSpeechItem> findBySessionIdAndCommandId(Long sessionId, String commandId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LiveSpeechItem i where i.id = :id")
    Optional<LiveSpeechItem> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LiveSpeechItem i where i.sessionId = :sessionId and i.commandId = :commandId")
    Optional<LiveSpeechItem> findForUpdate(@Param("sessionId") Long sessionId, @Param("commandId") String commandId);

    /** Playback order is submission order among whatever has finished synthesis. */
    List<LiveSpeechItem> findBySessionIdAndStatusAndReadyAtAfterOrderByIdAsc(Long sessionId, String status, Instant after);

    List<LiveSpeechItem> findBySessionIdAndStatusIn(Long sessionId, Collection<String> statuses);

    List<LiveSpeechItem> findTop30BySessionIdOrderByIdDesc(Long sessionId);

    List<LiveSpeechItem> findTop3BySessionIdAndKindAndProductIdAndStatusInOrderByIdDesc(
            Long sessionId, String kind, Long productId, Collection<String> statuses);

    Optional<LiveSpeechItem> findFirstBySessionIdAndKindAndStatusInAndIdLessThanOrderByIdDesc(
            Long sessionId, String kind, Collection<String> statuses, Long id);

    long countBySessionIdAndStatusInAndCreatedAtAfter(Long sessionId, Collection<String> statuses, Instant after);

    long countBySessionIdAndKindAndStatusInAndCreatedAtAfter(
            Long sessionId, String kind, Collection<String> statuses, Instant after);

    long countBySessionIdAndKindAndCreatedAtAfter(Long sessionId, String kind, Instant after);

    Optional<LiveSpeechItem> findFirstBySessionIdAndStatusInAndCreatedAtAfterOrderByIdAsc(
            Long sessionId, Collection<String> statuses, Instant after);

    /** A worker that died mid-task must not leave the session looking busy forever. */
    @Modifying(flushAutomatically = true)
    @Query("update LiveSpeechItem i set i.status = 'FAILED', i.errorMessage = :message, i.finishedAt = :now "
            + "where i.sessionId = :sessionId and i.kind = :kind and i.status in ('GENERATING', 'PENDING') "
            + "and i.createdAt <= :before")
    int failStale(@Param("sessionId") Long sessionId, @Param("kind") String kind, @Param("before") Instant before,
                  @Param("message") String message, @Param("now") Instant now);
}
