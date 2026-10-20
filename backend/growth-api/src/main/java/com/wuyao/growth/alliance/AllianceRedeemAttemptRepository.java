package com.wuyao.growth.alliance;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface AllianceRedeemAttemptRepository extends JpaRepository<AllianceRedeemAttempt, Long> {

    @Query("select a from AllianceRedeemAttempt a where a.userId = :userId and a.createdAt >= :since order by a.createdAt desc, a.id desc")
    List<AllianceRedeemAttempt> recent(@Param("userId") Long userId, @Param("since") Instant since, Pageable pageable);
}
