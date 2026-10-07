package com.wuyao.growth.live.reply;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LiveCommentRepository extends JpaRepository<LiveComment, Long> {

    Optional<LiveComment> findBySessionIdAndProviderAndExternalId(Long sessionId, String provider, String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LiveComment c where c.id = :id")
    Optional<LiveComment> findForUpdate(@Param("id") Long id);

    List<LiveComment> findTop50BySessionIdOrderByIdDesc(Long sessionId);

    long countBySessionIdAndModelUsedTrueAndCreatedAtAfter(Long sessionId, Instant after);

    List<LiveComment> findTop500BySessionIdAndKnowledgeGapTrueOrderByIdDesc(Long sessionId);
}
